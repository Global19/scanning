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
package org.eclipse.scanning.api.ui;

import java.text.MessageFormat;

import org.eclipse.scanning.api.event.status.StatusBean;

public interface IResultHandler<T extends StatusBean> extends IHandler<T> {
	
	/**
	 * Called to open the result from the beam.
	 * @param bean
	 * @throws Exception if something unexpected goes wrong
	 * @return true if result open handled ok, false otherwise. Normally if the user chooses not to proceed true is still returned.
	 */
	public boolean open(T bean) throws Exception;
	
	default public String getErrorMessage(T bean) {
		return MessageFormat.format("Cannot open {0} normally.\n\nPlease contact your support representative.", getLocation(bean));
	}
	
	default public String getLocation(T bean) {
		return bean.getRunDirectory();
	}
	
}
