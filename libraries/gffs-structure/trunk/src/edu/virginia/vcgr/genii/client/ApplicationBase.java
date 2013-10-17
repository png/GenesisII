package edu.virginia.vcgr.genii.client;

import java.io.File;
import java.io.Reader;
import java.io.Writer;
import java.util.regex.Pattern;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.morgan.util.io.GuaranteedDirectory;

import edu.virginia.vcgr.genii.client.cmd.ReloadShellException;
import edu.virginia.vcgr.genii.client.cmd.tools.ConnectTool;
import edu.virginia.vcgr.genii.client.configuration.ConfigurationManager;
import edu.virginia.vcgr.genii.client.configuration.DeploymentName;
import edu.virginia.vcgr.genii.client.context.CallingContextImpl;
import edu.virginia.vcgr.genii.client.context.ContextManager;
import edu.virginia.vcgr.genii.client.context.ICallingContext;
import edu.virginia.vcgr.genii.client.rns.RNSPath;
import edu.virginia.vcgr.genii.context.ContextType;

public class ApplicationBase
{
	static private Log _logger = LogFactory.getLog(ApplicationBase.class);

	// the optional environment variable that points at the installation location.
	// this becomes required if one is running the software under eclipse.
	static public final String INSTALLATION_DIR_ENVIRONMENT_VARIABLE = "GENII_INSTALL_DIR";

	// the environment variable whose value points at our state directory.
	static public final String USER_DIR_ENVIRONMENT_VARIABLE = "GENII_USER_DIR";

	// this property value can be used in certain property files as a placeholder that is
	// translated into the value of the user state directory variable (see above).
	static public final String USER_DIR_PROPERTY_VALUE = "env-GENII_USER_DIR";

	// loads the value for the genesis user state directory from the environment.
	static public String getUserDirFromEnvironment()
	{
		String value = System.getenv(USER_DIR_ENVIRONMENT_VARIABLE);
		// make this decision across the board, so we don't get caught short without
		// a default user state directory.
		if (value == null || value.length() == 0) {
			value = String.format("%s/%s", System.getProperty("user.home"), GenesisIIConstants.GENESISII_STATE_DIR_NAME);
		}
		return value;
	}

	// this provides a way for the osgi support to know we're running inside eclipse, which imposes
	// a different structure on the locations of files. the
	static public String getEclipseTrunkFromEnvironment()
	{
		return System.getenv(INSTALLATION_DIR_ENVIRONMENT_VARIABLE);
	}

	static public void setupUserDir(File userdir)
	{
		if (!userdir.exists()) {
			try {
				File userDirFile = new GuaranteedDirectory(userdir.getAbsolutePath(), true);
				if (!userDirFile.isDirectory()) {
					throw new RuntimeException("Path \"" + userdir.getAbsolutePath() + "\" is not a directory.");
				}
			} catch (Throwable e) {
				throw new RuntimeException("Unable to create directory \"" + userdir.getAbsolutePath() + "\".");
			}
		}
	}

	/**
	 * Prepares the static configuration manager
	 */
	static protected void prepareServerApplication()
	{
		ContainerProperties cProperties = ContainerProperties.getContainerProperties();
		String depName = cProperties.getDeploymentName();
		if (depName != null)
			System.setProperty(DeploymentName.DEPLOYMENT_NAME_PROPERTY, depName);

		String userDir = getUserDir(cProperties);
		ConfigurationManager configurationManager = ConfigurationManager.initializeConfiguration(userDir);

		setupUserDir(configurationManager.getUserDirectory());

		configurationManager.setRoleServer();
	}

	/**
	 * Prepares the static configuration manager (using the config files in the explicitConfigDir).
	 * If explicitConfigDir is null, the GENII_CONFIG_DIR is inspected. If that is not present (or
	 * empty), the default configuration location located in a well-known spot from the installation
	 * directory (as per the installation system property) is used;
	 * 
	 * @param explicitConfigDir
	 */
	static protected void prepareClientApplication()
	{
		String userDir = getUserDir(null);
		ConfigurationManager configurationManager = ConfigurationManager.initializeConfiguration(userDir);

		setupUserDir(configurationManager.getUserDirectory());

		configurationManager.setRoleClient();
	}

	static public String getUserDir(ContainerProperties cProperties)
	{
		String userDir = null;

		if (cProperties != null)
			userDir = cProperties.getUserDirectory();

		if (userDir == null)
			userDir = getUserDirFromEnvironment();

		try {
			File userDirFile = new GuaranteedDirectory(userDir, true);

			return userDirFile.getCanonicalPath();
		} catch (Throwable cause) {
			throw new RuntimeException("Unable to access or create state directory.", cause);
		}
	}

	public enum GridStates {
		CONNECTION_FAILED, // we were not connected, and we knew what to do, but that failed.
		CONNECTION_MEANS_UNKNOWN, // no breadcrumbs were left for how to get connected.
		CONNECTION_ALREADY_GOOD, // connection to the grid was already okay.
		CONNECTION_GOOD_NOW // there was no connection, but we have established one. shell must
							// reload.
	}

	static public GridStates establishGridConnection(Writer output, Writer error, Reader input)
	{
		ICallingContext callContext = null;
		try {
			callContext = ContextManager.getCurrentContext();
			if (callContext == null)
				callContext = new CallingContextImpl(new ContextType());
		} catch (Throwable e) {
			_logger.error("could not load or create calling context.");
		}
		if (callContext == null) {
			_logger.error("failed to build calling context.");
			return GridStates.CONNECTION_MEANS_UNKNOWN;
		}

		RNSPath currdir = callContext.getCurrentPath();
		if (currdir != null)
			return GridStates.CONNECTION_ALREADY_GOOD;

		String connectCmd = ContainerProperties.getContainerProperties().getConnectionCommand();
		if (_logger.isDebugEnabled())
			_logger.debug("grid connection command is: " + connectCmd);
		if ((connectCmd == null) || connectCmd.isEmpty()) {
			if (_logger.isDebugEnabled())
				_logger.debug("Did not find grid connection property; unknown how to get on grid.");
			return GridStates.CONNECTION_MEANS_UNKNOWN;
		}
		if (_logger.isDebugEnabled())
			_logger.debug("trying grid connection with parameters: " + connectCmd);

		// split up our line which is expected to be two quoted strings. those are our arguments.
		Pattern quoter = Pattern.compile("\" \"");
		String[] parameters = quoter.split(connectCmd, 2);
		if (parameters.length != 2) {
			_logger.error("did not find the grid connection command line in the proper format.  bailing out.");
			return GridStates.CONNECTION_MEANS_UNKNOWN;
		}
		parameters[0] = parameters[0].substring(1); // take off initial quote on first parm.
		parameters[1] = parameters[1].substring(0, parameters[1].length() - 1); // take off last
																				// quote on second
																				// parm.
		if (_logger.isDebugEnabled())
			_logger.debug("got arguments: [0]=" + parameters[0] + " [1]=" + parameters[1]);

		ConnectTool ct = new ConnectTool();
		try {
			for (int i = 0; i < parameters.length; i++)
				ct.addArgument(parameters[i]);
			ct.run(output, error, input);
		} catch (ReloadShellException e) {
			if (_logger.isDebugEnabled())
				_logger.debug("got newly connected; reloading grid shell");
			return GridStates.CONNECTION_GOOD_NOW;
		} catch (Throwable e) {
			_logger.error("failure during grid connection: " + e.getMessage(), e);
		}
		return GridStates.CONNECTION_FAILED;
	}
}