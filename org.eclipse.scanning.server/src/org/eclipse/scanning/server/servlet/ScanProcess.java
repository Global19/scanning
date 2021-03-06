/*-
 *******************************************************************************
 * Copyright (c) 2011, 2016 Diamond Light Source Ltd.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *    Matthew Gerring - initial API and implementation and/or initial documentation
 *******************************************************************************/
package org.eclipse.scanning.server.servlet;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.eclipse.scanning.api.IScannable;
import org.eclipse.scanning.api.MonitorRole;
import org.eclipse.scanning.api.ValidationException;
import org.eclipse.scanning.api.annotation.scan.AnnotationManager;
import org.eclipse.scanning.api.annotation.scan.PostConfigure;
import org.eclipse.scanning.api.annotation.scan.PreConfigure;
import org.eclipse.scanning.api.device.AbstractRunnableDevice;
import org.eclipse.scanning.api.device.IDeviceController;
import org.eclipse.scanning.api.device.IPausableDevice;
import org.eclipse.scanning.api.device.IRunnableDevice;
import org.eclipse.scanning.api.device.IRunnableDeviceService;
import org.eclipse.scanning.api.device.models.IMalcolmModel;
import org.eclipse.scanning.api.device.models.MalcolmModel;
import org.eclipse.scanning.api.event.EventException;
import org.eclipse.scanning.api.event.core.IConsumerProcess;
import org.eclipse.scanning.api.event.core.IPublisher;
import org.eclipse.scanning.api.event.scan.DeviceInformation;
import org.eclipse.scanning.api.event.scan.ScanBean;
import org.eclipse.scanning.api.event.scan.ScanRequest;
import org.eclipse.scanning.api.event.status.Status;
import org.eclipse.scanning.api.malcolm.IMalcolmDevice;
import org.eclipse.scanning.api.points.GeneratorException;
import org.eclipse.scanning.api.points.IDeviceDependentIterable;
import org.eclipse.scanning.api.points.IPointGenerator;
import org.eclipse.scanning.api.points.IPointGeneratorService;
import org.eclipse.scanning.api.points.IPosition;
import org.eclipse.scanning.api.scan.IFilePathService;
import org.eclipse.scanning.api.scan.ScanEstimator;
import org.eclipse.scanning.api.scan.ScanInformation;
import org.eclipse.scanning.api.scan.ScanningException;
import org.eclipse.scanning.api.scan.event.IPositioner;
import org.eclipse.scanning.api.scan.models.ScanModel;
import org.eclipse.scanning.api.script.IScriptService;
import org.eclipse.scanning.api.script.ScriptExecutionException;
import org.eclipse.scanning.api.script.ScriptRequest;
import org.eclipse.scanning.api.script.ScriptResponse;
import org.eclipse.scanning.api.script.UnsupportedLanguageException;
import org.eclipse.scanning.server.application.Activator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Object for running a scan.
 *
 * @author Matthew Gerring
 *
 */
public class ScanProcess implements IConsumerProcess<ScanBean> {

	private static final Logger logger = LoggerFactory.getLogger(ScanProcess.class);
	protected final ScanBean               bean;
	protected final IPublisher<ScanBean>   publisher;

	// Services
	private IPositioner                positioner;
	private IScriptService             scriptService;

	private IDeviceController          controller;
	private boolean                    blocking;

	public ScanProcess(ScanBean scanBean, IPublisher<ScanBean> response, boolean blocking) throws EventException {

		this.bean = scanBean;
		this.publisher = response;
		this.blocking = blocking;

		if (bean.getScanRequest().getStart()!=null || bean.getScanRequest().getEnd()!=null) {
			try {
				this.positioner = Services.getRunnableDeviceService().createPositioner();
			} catch (ScanningException e) {
				throw new EventException(e);
			}
		}

		this.scriptService = Services.getScriptService();

		bean.setPreviousStatus(Status.SUBMITTED);
		bean.setStatus(Status.QUEUED);
		broadcast(bean);
	}

	@Override
	public void pause() throws EventException {
		try {
			controller.pause(getClass().getName(), null);
		} catch (ScanningException | InterruptedException e) {
			throw new EventException(e);
		}
	}

	@Override
	public void resume() throws EventException  {
		try {
			controller.resume(getClass().getName());
		} catch (ScanningException | InterruptedException e) {
			throw new EventException(e);
		}
	}

	@Override
	public void terminate() throws EventException {

		if (bean.getStatus()==Status.COMPLETE) return; // Nothing to terminate.
		try {
			if (controller!=null) controller.abort(getClass().getName());
		} catch (ScanningException  | InterruptedException e) {
			throw new EventException(e);
		}
	}

	@Override
	public void execute() throws EventException {
		try {
			logger.debug("Starting to run : {}", bean);
			setFilePath(bean);
			IPointGenerator<?> gen = getGenerator(bean.getScanRequest());
			initializeMalcolmDevice(bean, gen);

			checkMonitors(gen);
			validateRequest(bean);

			// Move to a position if they set one
			setPosition(bean.getScanRequest().getStart(), "start");

			// Run a script, if any has been requested
			runScript(bean.getScanRequest().getBefore(), bean.getScanRequest()::setBeforeResponse);

			this.controller = createRunnableDevice(bean, gen);

			if (blocking) {  // Normally the case
                executeBlocking(controller, bean);
			} else {
				executeNonBlocking(controller, bean);
			}

			bean.setPreviousStatus(Status.RUNNING);
			bean.setStatus(Status.COMPLETE);
			bean.setPercentComplete(100);
			broadcast(bean);

	        // Intentionally do not catch EventException, that passes straight up.

		} catch (Exception ne) {
			logger.error("Cannot execute run "+getBean().getName()+" "+getBean().getUniqueId(), ne);
			bean.setPreviousStatus(Status.RUNNING);
			bean.setStatus(Status.FAILED);
			bean.setMessage(ne.getMessage());
			broadcast(bean);

			if (ne instanceof EventException) throw (EventException)ne;
			throw new EventException(ne);
		}
	}

	private void executeNonBlocking(IDeviceController controller, ScanBean bean) throws ScanningException, InterruptedException, TimeoutException, ExecutionException {

		logger.debug("Running non-blocking device {}", controller.getDevice().getName());
		controller.getDevice().start(null);

		long latchTime = Long.getLong("org.eclipse.scanning.server.servlet.asynchWaitTime", 500);
		logger.debug("Latching on device {} for {}", controller.getDevice().getName(), latchTime);
		controller.getDevice().latch(latchTime, TimeUnit.MILLISECONDS); // Wait for it to do a bit in case of errors.

		logger.warn("Cannot run end script when scan is async. (Scan has not been cancelled, after script has been ignored.)");
		logger.warn("Cannot perform end position when scan is async. (Scan has not been cancelled, end has been ignored.)");
	}

	private void executeBlocking(IDeviceController controller, ScanBean bean) throws ScanningException, InterruptedException, TimeoutException, ExecutionException, EventException, UnsupportedLanguageException, ScriptExecutionException {

		logger.debug("Running blocking controller {}", controller.getName());
		controller.getDevice().run(null); // Runs until done

		// Run a script, if any has been requested
		runScript(bean.getScanRequest().getAfter(), bean.getScanRequest()::setAfterResponse);
        setPosition(bean.getScanRequest().getEnd(), "end");
	}

	private void setPosition(IPosition pos, String location) throws ScanningException, InterruptedException {
		if (pos!=null) {
			positioner.setPosition(pos);
			logger.debug("The "+location+" position {} is reached.", pos);
		}
	}

	private void validateRequest(ScanBean bean) throws ValidationException, InstantiationException, IllegalAccessException {
		if (!Boolean.getBoolean("org.eclipse.scanning.server.servlet.scanProcess.disableValidate")) {
			logger.debug("Validating run : {}", bean);
			final ScanRequest<?> sr = bean.getScanRequest();
			if (sr.getDetectors()!=null && sr.getDetectors().isEmpty()) sr.setDetectors(null);
		    Services.getValidatorService().validate(sr);
			logger.debug("Validating passed : {}", bean);
		} else {
			logger.warn("The run {} has validation switched off.", bean);
		}
	}

	/**
	 * Checks the monitors in the scan request. This removes from the
	 * collection of monitor names the name of any monitor that is a scannable in the scan.
	 * Also, if the property {@code org.eclipse.scanning.server.useDefaultActivatedMonitors }
	 * is <code>true</code>, and the monitor list is empty, the default monitors are added.
	 * @param gen point generator
	 * @throws Exception
	 */
	private void checkMonitors(IPointGenerator<?> gen) throws Exception {
		Collection<String> monitorNamesPerPoint = bean.getScanRequest().getMonitorNamesPerPoint();
		Collection<String> monitorNamesPerScan = bean.getScanRequest().getMonitorNamesPerScan();

		// We set any activated monitors in the request if none have been specified.
		if (Boolean.getBoolean("org.eclipse.scanning.server.useDefaultActivatedMonitors")) {
			if (monitorNamesPerPoint == null) {
				monitorNamesPerPoint = getMonitors(MonitorRole.PER_POINT);
				logger.debug("Using default per point monitors: {}", monitorNamesPerPoint);
			}
			if (monitorNamesPerScan == null) {
				monitorNamesPerScan = getMonitors(MonitorRole.PER_SCAN);
				logger.debug("Using default per scan monitors: {}", monitorNamesPerScan);
			}
		}

		if (monitorNamesPerPoint != null) {
			// remove any monitors
			Collection<String> scannableNames = getScannableNames(gen);
			monitorNamesPerPoint = monitorNamesPerPoint.stream().filter(mon -> !scannableNames.contains(mon)).collect(Collectors.toList());

			bean.getScanRequest().setMonitorNamesPerPoint(monitorNamesPerPoint);
		}
		if (monitorNamesPerScan != null) {
			// remove any monitors
			Collection<String> scannableNames = getScannableNames(gen);
			monitorNamesPerScan = monitorNamesPerScan.stream().filter(mon -> !scannableNames.contains(mon)).collect(Collectors.toList());

			bean.getScanRequest().setMonitorNamesPerScan(monitorNamesPerScan);
		}
	}

	private Collection<String> getMonitors(MonitorRole monitorRole) throws Exception {

		final Collection<DeviceInformation<?>> scannables = Services.getConnector().getDeviceInformation();
		final List<String> ret = new ArrayList<String>();
		for (DeviceInformation<?> info : scannables) {
			if (info.isActivated() && info.getMonitorRole() == monitorRole) ret.add(info.getName());
		}
		return ret;
	}


	private void setFilePath(ScanBean bean) throws EventException {
		ScanRequest<?> req = bean.getScanRequest();

		// Set the file path to the next scan file path from the service
		// which manages scan names.
		if (req.getFilePath() == null) {
			IFilePathService fservice = Services.getFilePathService();
			if (fservice != null) {
				try {
					final String template = req.getSampleData() != null ? req.getSampleData().getName() : null;
					bean.setFilePath(fservice.getNextPath(template));
				} catch (Exception e) {
					throw new EventException(e);
				}
			} else {
				bean.setFilePath(null); // It is allowable to run a scan without a nexus file
			}
		} else {
			bean.setFilePath(req.getFilePath());
		}
		logger.debug("Nexus file path set to {}", bean.getFilePath());

	}

	/**
	 * Initialise the malcolm device with the point generator and the malcolm model
	 * with its output directory. This needs to be done before validation as these values
	 * are sent to the actual malcolm device over the connection for validation.
	 * @param gen
	 * @throws EventException
	 * @throws ScanningException
	 */
	private void initializeMalcolmDevice(ScanBean bean, IPointGenerator<?> gen) throws EventException, ScanningException {

		ScanRequest<?> req = bean.getScanRequest();

		// check for a malcolm device, if one is found, set its output dir on the model
		// and point generator on the malcolm device itself
		if (bean.getFilePath() == null) return;

		String malcolmDeviceName = null;
		MalcolmModel malcolmModel = null;
		final Map<String, Object> detectorMap = req.getDetectors();
		if (detectorMap == null) return;

		for (String detName : detectorMap.keySet()) {
			if (detectorMap.get(detName) instanceof MalcolmModel) {
				malcolmDeviceName = detName;
				malcolmModel = (MalcolmModel) detectorMap.get(detName);
				break;
			}
		}

		if (malcolmModel == null) return;

		// Set the malcolm output directory. This is new dir in the same parent dir as the
		// scan file and with the same name as the scan file (minus the file extension)
		final File scanFile = new File(bean.getFilePath());
		final File scanDir = scanFile.getParentFile();
		String scanFileNameNoExtn = scanFile.getName();
		final int dotIndex = scanFileNameNoExtn.indexOf('.');
		if (dotIndex != -1) {
			scanFileNameNoExtn = scanFileNameNoExtn.substring(0, dotIndex);
		}
		final File malcolmOutputDir = new File(scanDir, scanFileNameNoExtn);
		malcolmOutputDir.mkdir(); // create the new malcolm output directory for the scan
		malcolmModel.setFileDir(malcolmOutputDir.toString());
		logger.debug("Device {} set malcolm output dir to {}", malcolmModel.getName(), malcolmOutputDir);

		// Set the point generator for the malcolm device
		// We must set it explicitly here because validation checks for a generator and will fail.
		final IRunnableDeviceService service = Services.getRunnableDeviceService();
		IRunnableDevice<?> malcolmDevice = service.getRunnableDevice(malcolmDeviceName);
		((IMalcolmDevice<?>) malcolmDevice).setPointGenerator(gen);
		logger.debug("Malcolm device(s) initialized");
	}

	private void runScript(ScriptRequest req, Consumer<ScriptResponse<?>> cons) throws EventException, UnsupportedLanguageException, ScriptExecutionException {
		if (req==null) return; // Nothing to do
		if (scriptService==null) throw new ScriptExecutionException("No script service is available, cannot run script request "+req);
		ScriptResponse<?> res = scriptService.execute(req);
		logger.debug("Script ran with response {}.", res);
		cons.accept(res);
	}

	private IDeviceController createRunnableDevice(ScanBean bean, IPointGenerator<?> gen) throws ScanningException, EventException {

		ScanRequest<?> req = bean.getScanRequest();
		if (req==null) throw new ScanningException("There must be a scan request to run a scan!");

		try {
			final ScanModel scanModel = new ScanModel();
			IPointGenerator<?> generator = getGenerator(req);
			scanModel.setPositionIterable(generator);

			ScanEstimator estimator = new ScanEstimator(Services.getGeneratorService(), bean.getScanRequest());
			bean.setSize(estimator.getSize());
			scanModel.setFilePath(bean.getFilePath());

			scanModel.setDetectors(getDetectors(req.getDetectors()));
			scanModel.setScannables(getScannables(getScannableNames(generator)));
			scanModel.setMonitorsPerPoint(getScannables(req.getMonitorNamesPerPoint()));
			scanModel.setMonitorsPerScan(getScannables(req.getMonitorNamesPerScan()));
			scanModel.setScanMetadata(req.getScanMetadata());
			scanModel.setBean(bean);

			ScanInformation scanInfo = new ScanInformation(estimator);
			scanInfo.setFilePath(bean.getFilePath());
			scanInfo.setScannableNames(getScannableNames(generator));
			scanModel.setScanInformation(scanInfo);

			configureDetectors(req.getDetectors(), scanModel, generator);

			IPausableDevice<ScanModel> device = (IPausableDevice<ScanModel>) Services.getRunnableDeviceService().createRunnableDevice(scanModel, publisher, false);
			IDeviceController controller = Services.getWatchdogService().create(device);
			if (controller.getObjects()!=null) scanModel.setAnnotationParticipants(controller.getObjects());

			logger.debug("Configuring {} with {}", device.getName(), scanModel);
			device.configure(scanModel);
			logger.debug("Configured {}", device.getName());
		    return controller;

		} catch (Exception e) {
			bean.setStatus(Status.FAILED);
			bean.setMessage(e.getMessage());
			broadcast(bean);
			if (e instanceof EventException) throw (EventException)e;
			throw new EventException(e);
		}
	}

	private void configureDetectors(Map<String, Object> dmodels, ScanModel model, IPointGenerator<?> generator) throws Exception {

		logger.debug("Configuring detectors {}", dmodels!=null?dmodels.keySet():null);
		for (IRunnableDevice<?> device : model.getDetectors()) {

			AnnotationManager manager = new AnnotationManager(Activator.createResolver());
			manager.addDevices(device);
			manager.addContext(model.getScanInformation());

			@SuppressWarnings("unchecked")
			IRunnableDevice<Object> odevice = (IRunnableDevice<Object>)device;

			if (!dmodels.containsKey(odevice.getName())) continue; // Nothing to configure
			Object dmodel = dmodels.get(odevice.getName());

			manager.invoke(PreConfigure.class, dmodel, generator, model, bean, publisher);
			if (odevice instanceof AbstractRunnableDevice) {
				((AbstractRunnableDevice<?>)odevice).setBean(bean);
			}
			odevice.configure(dmodel);
			manager.invoke(PostConfigure.class, dmodel, generator, model, bean, publisher);
		}
		logger.debug("Configured detectors {}", dmodels!=null?dmodels.keySet():null);
	}

	private Collection<String> getScannableNames(Iterable<IPosition> gen) {

		Collection<String> names = null;
		if (gen instanceof IDeviceDependentIterable) {
			names = ((IDeviceDependentIterable)gen).getScannableNames();
		}
		if (names==null) {
			names = gen.iterator().next().getNames();
		}
		return names;
	}

	private IPointGenerator<?> getGenerator(ScanRequest<?> req) throws GeneratorException {
		IPointGeneratorService service = Services.getGeneratorService();
		if (req.getDetectors() != null) {
			// if theres a malcolm device, set the duration of the compound model to its exposure time
			req.getDetectors().values().stream()
				.filter(IMalcolmModel.class::isInstance).map(IMalcolmModel.class::cast)
				.findFirst().ifPresent(model -> req.getCompoundModel().setDuration(model.getExposureTime()));
		}

		return service.createCompoundGenerator(req.getCompoundModel());
	}

	private List<IRunnableDevice<?>> getDetectors(Map<String, ?> detectors) throws EventException {

		if (detectors==null) return null;
		try {

			final List<IRunnableDevice<?>> ret = new ArrayList<>(3);

			final IRunnableDeviceService service = Services.getRunnableDeviceService();

			for (String name : detectors.keySet()) {
				Object dmodel = detectors.get(name);
				IRunnableDevice<Object> detector = service.getRunnableDevice(name);
				if (detector==null) {
					detector = service.createRunnableDevice(dmodel, false);
					detector.setName(name); // Not sure if this is ok. For now name must match that in table
				}
				ret.add(detector);
			}

			return ret;

		} catch (ScanningException ne) {
			throw new EventException(ne);
		}
	}

	private List<IScannable<?>> getScannables(Collection<String> scannableNames) throws EventException {
		// used to get the monitors and the metadata scannables
		if (scannableNames==null) return null;
		try {
			final List<IScannable<?>> ret = new ArrayList<>(3);
			for (String name : scannableNames) ret.add(Services.getConnector().getScannable(name));
			return ret;
		} catch (ScanningException ne) {
			throw new EventException(ne);
		}
	}

	private void broadcast(ScanBean bean) throws EventException {
		if (publisher!=null) {
			publisher.broadcast(bean);
		}
	}

	@Override
	public ScanBean getBean() {
		return bean;
	}

	@Override
	public IPublisher<ScanBean> getPublisher() {
		return publisher;
	}

}
