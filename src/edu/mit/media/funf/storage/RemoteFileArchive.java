/**
 * 
 * Funf: Open Sensing Framework
 * Copyright (C) 2010-2011 Nadav Aharony, Wei Pan, Alex Pentland.
 * Acknowledgments: Alan Gardner
 * Contact: nadav@media.mit.edu
 * 
 * This file is part of Funf.
 * 
 * Funf is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 * 
 * Funf is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public
 * License along with Funf. If not, see <http://www.gnu.org/licenses/>.
 * 
 */
package edu.mit.media.funf.storage;

import java.io.File;

/**
 * Interface for representing file archives that are not on the device.
 *
 */
public interface RemoteFileArchive {

	/**
	 * Synchronously add the file to the remote archive
	 * @param file the File to upload
	 * @return true if successfully added, false otherwise
	 */
	public boolean add(File file) throws Exception;
	
	
	/**
	 * A unique string that represents this remote archive.  These will mostly be URIs,
	 * but implementation is dependent on implementation.
	 * @return
	 */
	public String getId();
}
