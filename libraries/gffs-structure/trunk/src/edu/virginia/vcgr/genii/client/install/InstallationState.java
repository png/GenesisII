package edu.virginia.vcgr.genii.client.install;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.net.URL;
import java.util.HashMap;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.morgan.util.io.StreamUtils;

import edu.virginia.vcgr.genii.client.utils.flock.FileLock;
import edu.virginia.vcgr.genii.client.utils.flock.FileLockException;

public class InstallationState implements Serializable
{
	static final long serialVersionUID = 0L;

	static private Log _logger = LogFactory.getLog(InstallationState.class);

	// Map of deployment name to port
	private HashMap<String, ContainerInformation> _runningContainers;

	private InstallationState()
	{
		_runningContainers = new HashMap<String, ContainerInformation>();
	}

	static private InstallationState readState(File installFile)
	{
		if (_logger.isTraceEnabled())
			_logger.debug("reading install state from " + installFile);

		FileInputStream fin = null;

		try {
			fin = new FileInputStream(installFile);
			ObjectInputStream ois = new ObjectInputStream(fin);
			return (InstallationState) ois.readObject();
		} catch (FileNotFoundException fnfe) {
			// No problem, we just haven't got state yet
			return new InstallationState();
		} catch (ClassNotFoundException fnfe) {
			// Corrupt state
			_logger.error("Corrupt state (class not found) found in installation description -- continuing with empty state.", fnfe);
			return new InstallationState();
		} catch (IOException ioe) {
			// Corrupt state
			_logger.error("Corrupt state (IO exception) found in installation description -- continuing with empty state.", ioe);
			return new InstallationState();
		} finally {
			StreamUtils.close(fin);
		}
	}

	static private void writeState(File installFile, InstallationState state) throws IOException
	{
		if (_logger.isTraceEnabled())
			_logger.debug("writing install state to " + installFile);

		FileOutputStream fout = null;

		try {
			fout = new FileOutputStream(installFile);
			ObjectOutputStream oos = new ObjectOutputStream(fout);
			oos.writeObject(state);
			oos.flush();
			oos.close();
		} catch (IOException ioe) {
			_logger.fatal("Unable to write installation state to permenant storage.", ioe);
			throw ioe;
		} finally {
			StreamUtils.close(fout);
		}
	}

	static public HashMap<String, ContainerInformation> getRunningContainers() throws FileLockException
	{
		File installFile = getInstallationStateFile();
		FileLock flock = null;

		try {
			flock = FileLock.acquireLock(installFile);
			return readState(installFile)._runningContainers;
		} finally {
			StreamUtils.close(flock);
		}
	}

	static public void addRunningContainer(String deploymentName, URL containerURL) throws IOException, FileLockException
	{
		File installFile = getInstallationStateFile();
		FileLock flock = null;

		try {
			flock = FileLock.acquireLock(installFile);
			InstallationState state = readState(installFile);
			state._runningContainers.put(deploymentName, new ContainerInformation(deploymentName, containerURL));
			writeState(installFile, state);
		} finally {
			StreamUtils.close(flock);
		}
	}

	static public void removeRunningContainer(String deploymentName) throws IOException, FileLockException
	{
		File installFile = getInstallationStateFile();
		FileLock flock = null;

		try {
			flock = FileLock.acquireLock(installFile);
			InstallationState state = readState(installFile);
			state._runningContainers.remove(deploymentName);
			writeState(installFile, state);
		} finally {
			StreamUtils.close(flock);
		}
	}

	static private File getInstallationStateFile()
	{
		/*
		 * This isn't a perfect solution to bug #65, but until we have something better, it will have to do. The problem is that the user's
		 * home directory may not be a local partition which could cause troubles.
		 * 
		 * (CAK: bug 65 must be in some older tracking system, since current trac bug #65 is totally different.)
		 */
		File installFile = new File(System.getProperty("user.home"), ".installation-state-gffs");
		return installFile;
	}
}
