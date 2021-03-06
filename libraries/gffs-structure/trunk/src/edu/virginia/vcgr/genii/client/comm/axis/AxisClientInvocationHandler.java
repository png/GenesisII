/*
 * Copyright 2006 University of Virginia
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the License. You may
 * obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 */
package edu.virginia.vcgr.genii.client.comm.axis;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.SocketException;
import java.net.URL;
import java.net.UnknownHostException;
import java.rmi.ConnectException;
import java.rmi.RemoteException;
import java.security.GeneralSecurityException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedList;

import javax.activation.DataHandler;
import javax.mail.util.ByteArrayDataSource;
import javax.xml.namespace.QName;
import javax.xml.soap.SOAPException;

import org.apache.axis.AxisFault;
import org.apache.axis.SimpleChain;
import org.apache.axis.attachments.AttachmentPart;
import org.apache.axis.client.Call;
import org.apache.axis.client.Stub;
import org.apache.axis.message.SOAPHeaderElement;
import org.apache.axis.types.URI;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.morgan.util.GUID;
import org.ws.addressing.EndpointReferenceType;

import edu.virginia.cs.vcgr.genii._2006._12.resource_simple.TryAgainFaultType;
import edu.virginia.vcgr.appmgr.version.Version;
import edu.virginia.vcgr.genii.client.ClientProperties;
import edu.virginia.vcgr.genii.client.GenesisIIConstants;
import edu.virginia.vcgr.genii.client.cache.ResourceAccessMonitor;
import edu.virginia.vcgr.genii.client.cache.unified.CacheManager;
import edu.virginia.vcgr.genii.client.cache.unified.subscriptionmanagement.NotificationMessageIndexProcessor;
import edu.virginia.vcgr.genii.client.comm.ClientUtils;
import edu.virginia.vcgr.genii.client.comm.CommConstants;
import edu.virginia.vcgr.genii.client.comm.GenesisIIEndpointInformation;
import edu.virginia.vcgr.genii.client.comm.GeniiSOAPHeaderConstants;
import edu.virginia.vcgr.genii.client.comm.MethodDescription;
import edu.virginia.vcgr.genii.client.comm.ResolutionContext;
import edu.virginia.vcgr.genii.client.comm.attachments.AttachmentType;
import edu.virginia.vcgr.genii.client.comm.attachments.GeniiAttachment;
import edu.virginia.vcgr.genii.client.comm.axis.AxisServiceAndStubTracking.AcquiredStubRecord;
import edu.virginia.vcgr.genii.client.comm.axis.AxisServiceAndStubTracking.AcquiredStubsList;
import edu.virginia.vcgr.genii.client.comm.axis.AxisServiceAndStubTracking.ServiceRecord;
import edu.virginia.vcgr.genii.client.comm.axis.security.ISecurityRecvHandler;
import edu.virginia.vcgr.genii.client.comm.axis.security.ISecuritySendHandler;
import edu.virginia.vcgr.genii.client.comm.axis.security.MessageSecurity;
import edu.virginia.vcgr.genii.client.comm.axis.security.VcgrSslSocketFactory;
import edu.virginia.vcgr.genii.client.configuration.ConfigurationManager;
import edu.virginia.vcgr.genii.client.configuration.ConfigurationUnloadedListener;
import edu.virginia.vcgr.genii.client.configuration.DeploymentName;
import edu.virginia.vcgr.genii.client.configuration.Installation;
import edu.virginia.vcgr.genii.client.configuration.KeystoreSecurityConstants;
import edu.virginia.vcgr.genii.client.context.ContextManager;
import edu.virginia.vcgr.genii.client.context.ICallingContext;
import edu.virginia.vcgr.genii.client.invoke.IFinalInvoker;
import edu.virginia.vcgr.genii.client.invoke.InvocationInterceptorManager;
import edu.virginia.vcgr.genii.client.naming.EPRUtils;
import edu.virginia.vcgr.genii.client.naming.NameResolutionFailedException;
import edu.virginia.vcgr.genii.client.naming.WSName;
import edu.virginia.vcgr.genii.client.notification.NotificationBrokerConstants;
import edu.virginia.vcgr.genii.client.resource.ResourceException;
import edu.virginia.vcgr.genii.client.resource.TypeInformation;
import edu.virginia.vcgr.genii.client.security.GenesisIISecurityException;
import edu.virginia.vcgr.genii.client.security.PermissionDeniedException;
import edu.virginia.vcgr.genii.client.security.axis.AuthZSecurityException;
import edu.virginia.vcgr.genii.client.utils.DetailedLogger;
import edu.virginia.vcgr.genii.security.TransientCredentials;
import edu.virginia.vcgr.genii.security.axis.MessageLevelSecurityRequirements;
import edu.virginia.vcgr.genii.security.credentials.ClientCredentialTracker;
import edu.virginia.vcgr.genii.security.credentials.CredentialCache;
import edu.virginia.vcgr.genii.security.credentials.CredentialWallet;

/**
 * manages RPC calls using our defined axis services. handles resolution and replication issues also.
 */
public class AxisClientInvocationHandler implements InvocationHandler, IFinalInvoker
{
	static Log _logger = LogFactory.getLog(AxisClientInvocationHandler.class);

	static private MessageLevelSecurityRequirements __minClientMessageSec = null;

	// records how many rpcs are going on right now.
	static private volatile Integer _activeClients = 0;

	static {
		/*
		 * add linkage to wipe our loaded config stuff in the event the config manager reloads.
		 */
		ConfigurationManager.addConfigurationUnloadListener(new ConfigUnloadListener());
	}

	// how many times will we allow the credentials to be re-sent before we fail the operation?
	public static int MAXIMUM_CREDENTIAL_RESENDING_ATTEMPTS = 4;

	public static class ConfigUnloadListener implements ConfigurationUnloadedListener
	{
		public void notifyUnloaded()
		{
			synchronized (AxisClientInvocationHandler.class) {
				__minClientMessageSec = null;
			}
		}
	}

	private EndpointReferenceType _epr;

	// future: this would be a good place to use a shorter timeout on containers...
	private Integer _timeout = null;

	private ICallingContext _callContext;

	private AttachmentType _attachmentType = AttachmentType.DIME;
	private Collection<GeniiAttachment> _outAttachments = null;
	private Collection<GeniiAttachment> _inAttachments = null;
	private GenesisIIEndpointInformation _lastEndpointInfo = null;

	private HashMap<MethodDescription, Object> _portMethods = new HashMap<MethodDescription, Object>();

	// locators are the "types" of classes that we can instantiate services for.
	private Class<?>[] _locators = null;
	private AxisClientInvocationHandler _parentHandler = null;

	private URL _serviceURL = null;

	/*
	 * these are the stubs we need to release when done with our call. note that this list is not synchronized because it is not static and is
	 * a member of the axis client invocation object, which is always created anew for each rpc.
	 */
	private AcquiredStubsList _acquiredStubs = new AcquiredStubsList();

	/**
	 * constructs a handler for rpc calls using the "locators" list of classes to create axis services connecting to the "epr" (host, port,
	 * container id).
	 */
	public AxisClientInvocationHandler(Class<?>[] locators, EndpointReferenceType epr, ICallingContext callContext)
		throws ResourceException, GenesisIISecurityException
	{
		// AxisServiceAndStubTracking.recordHandlerCreationAndTakeOutTrashIfAppropriate();

		try {
			_epr = epr;

			if (callContext == null) {
				throw new ResourceException("calling context passed in as null");
			}
			_callContext = callContext.deriveNewContext();
			_callContext.setSingleValueProperty(GenesisIIConstants.NAMING_CLIENT_CONFORMANCE_PROPERTY, "true");
			_locators = locators;

			// create the locator and add the methods to our list.
			for (Class<?> locator : locators) {
				ServiceRecord reco = AxisServiceAndStubTracking.createServiceInstance(locator);
				addMethods(reco);
			}
		} catch (IOException ioe) {
			throw new ResourceException("Error creating secure client stub: " + ioe.getMessage(), ioe);
		}

	}

	@SuppressWarnings("unchecked")
	private static <HandlerClass> ArrayList<HandlerClass> getHandler(SimpleChain handlerChain, Class<HandlerClass> handlerClass)
	{
		ArrayList<HandlerClass> retval = new ArrayList<HandlerClass>();

		if (handlerChain != null) {
			for (org.apache.axis.Handler h : handlerChain.getHandlers()) {
				if (h instanceof SimpleChain) {
					retval.addAll(getHandler((SimpleChain) h, handlerClass));
				} else if (handlerClass.isAssignableFrom(h.getClass())) {
					retval.add((HandlerClass) h);
				}
			}
		}
		return retval;
	}

	/**
	 * Retrieves the client's minimum allowable level of message security
	 */
	static private synchronized MessageLevelSecurityRequirements getMinClientMessageSec() throws AuthZSecurityException
	{
		if (__minClientMessageSec != null) {
			return __minClientMessageSec;
		}

		String minMessageSecurity =
			Installation.getDeployment(new DeploymentName()).security().getProperty(KeystoreSecurityConstants.Client.MESSAGE_MIN_CONFIG_PROP);

		__minClientMessageSec = new MessageLevelSecurityRequirements(minMessageSecurity);
		return __minClientMessageSec;
	}

	public synchronized void configureSecurity(Stub stubInstance)
		throws GenesisIISecurityException, GeneralSecurityException, ResourceException
	{
		Object confProp = stubInstance._getProperty(AxisServiceAndStubTracking.STUB_CONFIGURED);
		if ((confProp != null) && (confProp.toString() != "")) {
			// this one is already configured.
			return;
		}
		stubInstance._setProperty(AxisServiceAndStubTracking.STUB_CONFIGURED, AxisServiceAndStubTracking.STUB_CONFIGURED);

		// new call to turn on session tracking, and hopefully provide better performance.
		stubInstance.setMaintainSession(true);

		stubInstance._setProperty("attachments.implementation", "org.apache.axis.attachments.AttachmentsImpl");

		// determine the level of message security we need
		MessageLevelSecurityRequirements minClientMessageSec = getMinClientMessageSec();
		MessageLevelSecurityRequirements minResourceSec = EPRUtils.extractMinMessageSecurity(_epr);
		MessageLevelSecurityRequirements neededMsgSec = minClientMessageSec.computeUnion(minResourceSec);

		// perform resource-AuthN as specified in the client config file
		try {
			AxisServiceAndStubTracking.validateCertificateChain(_epr);
		} catch (Exception e) {
			if (minClientMessageSec.isWarn()) {
				/*
				 * the security level is set to just warning, and this is as loud of a warning as we want to emit. otherwise we're just
				 * constantly complaining that the message security level was set to warn only.
				 */
				if (_logger.isTraceEnabled())
					_logger.trace("Cannot confirm trusted identity for " + _epr.getAddress().toString());
			} else {
				throw new GenesisIISecurityException("EPR for " + _epr.getAddress().toString() + " is untrusted: " + e.getMessage(), e);
			}
		}

		/*
		 * prepare a message security data structure for the message context.
		 */
		MessageSecurity msgSecData = null;
		X509Certificate[] chain = EPRUtils.extractCertChain(_epr);
		URI epi = EPRUtils.extractEndpointIdentifier(_epr);
		msgSecData = new MessageSecurity(neededMsgSec, chain, epi);
		stubInstance._setProperty(CommConstants.MESSAGE_SEC_CALL_DATA, msgSecData);

		try {
			ServiceRecord reco = _acquiredStubs.getServiceRecordForStub(stubInstance);
			if (reco == null) {
				throw new AuthZSecurityException("failed to load service record for stub!  stub is: " + stubInstance);
			}

			/*
			 * configure the send handler(s), working backwards so as to set the last one that actually does work to serialize the message.
			 */
			ArrayList<ISecuritySendHandler> sendHandlers =
				getHandler((SimpleChain) reco._providerConfig.getGlobalRequest(), ISecuritySendHandler.class);
			boolean serializerFound = false;
			for (int i = sendHandlers.size() - 1; i >= 0; i--) {
				ISecuritySendHandler h = sendHandlers.get(i);
				if (h.configure(_callContext, msgSecData) && !serializerFound) {
					serializerFound = true;
					h.setToSerialize();
				}
			}

			// configure the recv handler(s)
			ArrayList<ISecurityRecvHandler> recvHandlers =
				getHandler((SimpleChain) reco._providerConfig.getGlobalResponse(), ISecurityRecvHandler.class);
			for (ISecurityRecvHandler h : recvHandlers) {
				h.configure(_callContext);
			}
		} catch (Exception e) {
			throw new ResourceException("Unable to configure security: " + e.getMessage(), e);
		}
	}

	private AxisClientInvocationHandler cloneHandlerForNewEPR(EndpointReferenceType epr) throws ResourceException, GenesisIISecurityException
	{
		try {
			AxisClientInvocationHandler newHandler = new AxisClientInvocationHandler(_locators, epr, _callContext);
			if (_outAttachments != null)
				newHandler._outAttachments = new LinkedList<GeniiAttachment>(_outAttachments);
			newHandler._attachmentType = _attachmentType;
			newHandler._parentHandler = this;
			return newHandler;
		} catch (Throwable t) {
			if (_logger.isDebugEnabled())
				_logger.debug("Attempt to create new AxisClientInvocationHandle failed.", t);
			return null;
		}
	}

	@Override
	public void finalize() throws Throwable
	{
		try {
			releaseStubs();
		} finally {
			super.finalize();
		}
	}

	/**
	 * return all the acquired stubs to the pool. this should at least be called by the finalizer for the class. it may also be called once
	 * the caller/owner *knows* that the axis RPC call is done and will never be re-attempted or reused.
	 */
	private void releaseStubs()
	{
		try {
			_portMethods.clear();
			_acquiredStubs.releaseAllStubs();
		} catch (Throwable t) {
			_logger.error("crashed while releasing stubs!?", t);
		}
	}

	private void addMethods(ServiceRecord reco) throws MalformedURLException, ResourceException
	{
		try {
			if (_epr.getAddress().get_value().toString().equals(WSName.UNBOUND_ADDRESS))
				if (_logger.isDebugEnabled())
					_logger.debug("Processing unbound address in AxisClientInvocationHandler");
			_serviceURL = new URL(_epr.getAddress().get_value().toString());
		} catch (java.net.MalformedURLException mue) {
			if (_epr.getAddress().get_value().toString().equals(WSName.UNBOUND_ADDRESS))
				_serviceURL = null;
			else
				throw mue;
		}

		if (_serviceURL == null) {
			// this is not a normal thing, so let's bail if there's no url.
			throw new ResourceException("failed to determine service URL from EPR member!  _epr=" + _epr);
		}

		try {
			Stub stubInstance = null;

			Date startStubbing = new Date();
			// stubInstance = AxisServiceAndStubTracking.getStubCache().getStub(reco._service, _serviceURL);

			// if (stubInstance != null) {
			// long duration = (new Date()).getTime() - startStubbing.getTime();
			// _acquiredStubs.add(new AcquiredStubRecord(stubInstance, _serviceURL, reco));
			// if (AxisServiceAndStubTracking.enableExtraLogging && _logger.isDebugEnabled())
			// _logger.debug("reusing stub instance " + stubInstance + " for url " + _serviceURL + " took " + duration + " ms");
			// }

			Method locatorPortTypeMethod = ClientUtils.getLocatorPortTypeMethod(reco._service.getClass());
			if (stubInstance == null) {
				stubInstance = (Stub) locatorPortTypeMethod.invoke(reco._service, new Object[] { _serviceURL });
				long duration = (new Date()).getTime() - startStubbing.getTime();
				if (AxisServiceAndStubTracking.enableExtraLogging && _logger.isDebugEnabled())
					_logger.debug("creating new stub instance " + stubInstance + " for url " + _serviceURL + " took " + duration + " ms");
				// add this new stub so it will be released when this object is disposed.
				_acquiredStubs.add(new AcquiredStubRecord(stubInstance, _serviceURL, reco));
			}

			if (_epr != null) {
				stubInstance._setProperty(CommConstants.TARGET_EPR_PROPERTY_NAME, _epr);
			}
			if (_callContext != null) {
				stubInstance._setProperty(CommConstants.CALLING_CONTEXT_PROPERTY_NAME, _callContext);
			}

			// Use the return type to get the methods that this stub supports
			Method[] ms = locatorPortTypeMethod.getReturnType().getMethods();
			for (Method m : ms) {
				_portMethods.put(new MethodDescription(m), stubInstance);
			}
		} catch (InvocationTargetException ite) {
			Throwable t = ite;
			_logger.error("addMethods saw exception", ite);
			if (ite.getCause() != null)
				t = ite.getCause();
			if (t != null) {
				if (t instanceof ResourceException)
					throw (ResourceException) t;
				else
					throw new ResourceException(t.toString(), t);
			} else
				throw new ResourceException(ite.toString(), ite);
		} catch (Exception e) {
			throw new ResourceException("Unable to locate appropriate stub.", e);
		}
	}

	static private InvocationInterceptorManager _manager = null;

	synchronized static private InvocationInterceptorManager getManager()
	{
		if (_manager == null) {
			_manager = (InvocationInterceptorManager) ConfigurationManager.getCurrentConfiguration().getClientConfiguration()
				.retrieveSection(new QName("http://vcgr.cs.virginia.edu/Genesis-II", "client-pipeline"));
		}

		if (_manager == null) {
			_logger.error("Couldn't find client pipeline configuration.");
			return new InvocationInterceptorManager();
		}

		return _manager;
	}

	@Override
	public Object invoke(Object target, Method m, Object[] params) throws Throwable
	{
		Object toReturn = null;
		synchronized (_activeClients) {
			_activeClients++;
		}

		try {
			InvocationInterceptorManager mgr = getManager();
			toReturn = mgr.invoke(getTargetEPR(), _callContext, this, m, params);
		} catch (PermissionDeniedException e) {
			_logger.info(e.getLocalizedMessage());
			throw e;
		} catch (GenesisIISecurityException e) {
			_logger.info(e.getLocalizedMessage());
			throw e;
		} catch (Throwable t) {
			String msg = "exception occurred during invoke";
			// we don't care below if t.getMessage() is null, since the extraction methods check for that.
			String asset = edu.virginia.vcgr.genii.client.security.PermissionDeniedException.extractAssetDenied(t.getMessage());
			String method = edu.virginia.vcgr.genii.client.security.PermissionDeniedException.extractMethodName(t.getMessage());
			if ((method != null) && (asset != null)) {
				msg = t.getLocalizedMessage() + "; permission denied on \"" + asset + "\" (in method \"" + method;
				_logger.info(msg);
			} else {
				_logger.error(msg, t);
			}

			if (_logger.isDebugEnabled()) {
				ICallingContext context;
				try {
					if (_logger.isTraceEnabled()) {
						context = ContextManager.getCurrentContext();
						TransientCredentials tc = TransientCredentials.getTransientCredentials(context);
						_logger.debug("invocation that was denied has these creds in call context: " + tc.toString());
					}
				} catch (Exception e2) {
					String msg2 = "could not load calling context to inspect credentials for failed IDP.";
					_logger.error(msg2, e2);
				}
			}

			throw t;
		} finally {
			synchronized (_activeClients) {
				_activeClients--;
			}
		}

		return toReturn;
	}

	static public boolean isActive()
	{
		int curr;
		synchronized (_activeClients) {
			curr = _activeClients;
		}
		return curr > 0;
	}

	static public int getActiveClients()
	{
		int curr;
		synchronized (_activeClients) {
			curr = _activeClients;
		}
		return curr;
	}

	static private boolean isConnectionException(Throwable cause)
	{
		while (cause != null) {
			if (cause instanceof ConnectException)
				return true;
			if (cause instanceof SocketException) {
				if (cause.getMessage().contains("Connection reset"))
					return true;
			}
			cause = cause.getCause();
		}

		return false;
	}

	/**
	 * added resolution code - 1/07 - jfk3w, revamped resolution code 4/11/07 - jfk3w.
	 */
	public Object finalInvoke(Object obj, Method calledMethod, Object[] arguments) throws Throwable
	{
		EndpointReferenceType origEPR = getTargetEPR();
		ResolutionContext context = null;
		int baseDelay = 100;
		int baseTwitter = 25;
		int attempt = 0;
		long startAttempt = 0L;
		int timeout = (_timeout != null) ? _timeout.intValue() : ClientProperties.getClientProperties().getClientTimeout();
		TypeInformation type = null;

		ResourceAccessMonitor.reportResourceUsage(origEPR);

		while (true) {
			attempt++;
			startAttempt = System.currentTimeMillis();
			context = new ResolutionContext(origEPR, (_parentHandler == null));
			_inAttachments = null;
			try {
				return resolveAndInvoke(context, calledMethod, arguments, timeout);
			} catch (Throwable cause) {
				long duration = System.currentTimeMillis() - startAttempt;
				if (_logger.isDebugEnabled()) {
					Throwable peeled = cause;
					while (peeled instanceof AxisFault) {
						if (peeled.getCause() != null) {
							peeled = peeled.getCause();
						} else {
							break;
						}
					}
					_logger.debug("resolveAndInvoke took " + duration + "ms before exception " + peeled.getClass().getCanonicalName());
				}
				if (!isConnectionException(cause)) {
					if (_logger.isDebugEnabled())
						_logger.debug("Unable to communicate with endpoint (not a retryable-exception).");
					throw cause;
				} else {
					/*
					 * Presumably, here I need to invalidate the cache for all entries that belongs to that particular container.
					 * 
					 * ASG July 2015, this is done in resolveandinvoke
					 */
				}
				if (type == null)
					type = new TypeInformation(_epr);
				int maxAttempts = (type.isEpiResolver() ? 1 : 5);
				if (attempt >= maxAttempts) {
					if (_logger.isDebugEnabled())
						_logger.debug("Unable to communicate with endpoint after " + attempt + " attempts.");
					throw cause;
				}
				/*
				 * deltaCommunicate is the total amount of time spent on the attempt, including time spent talking to the resolver and each
				 * instance. If this single attempt took too long, then don't make another attempt.
				 */
				long deltaCommunicate = System.currentTimeMillis() - startAttempt;
				if (deltaCommunicate > ClientProperties.getClientProperties().getMaximumRetryTime()) {
					if (_logger.isDebugEnabled())
						_logger.debug("Unable to communicate with endpoint after " + deltaCommunicate + " millis");
					throw cause;
				}
				try {
					// Sleep for a random period in the range of (-bt/2 ... +bt/2).
					int twitter = (int) (Math.random() * baseTwitter) - (baseTwitter >> 1);
					int sleepTime = baseDelay + twitter;
					if (_logger.isDebugEnabled())
						_logger.debug("Exponential backoff delay of " + sleepTime + " for an exception.");
					Thread.sleep(sleepTime);
				} catch (InterruptedException ie) {
					Thread.currentThread().isInterrupted();
					// Don't have to worry about it.
				}
				baseDelay <<= 1;
				baseTwitter <<= 1;
			} finally {
				_outAttachments = null;
			}
		}
	}

	/**
	 * if an exception occurs during invocation, this checks the exception to see if it's an omitted credentials message. if the operation
	 * should be retried after this method corrects the credential records, then true is returned and the handler should continue the big loop
	 * in resolveAndInvoke. if false is returned, then normal exception handling should take over.
	 */
	boolean testForCredentialStreamliningIssues(Throwable throwable, int missingCredsAttemptCounter, AxisClientInvocationHandler handler)
	{
		boolean credOmittedMessage =
			(throwable.getMessage() != null) && throwable.getMessage().contains(CredentialWallet.OMMITTED_CREDENTIAL_SENTINEL);
		boolean accessDeniedMessage =
			(throwable.getMessage() != null) && throwable.getMessage().contains(GenesisIIConstants.ACCESS_DENIED_SENTINEL);

		/*
		 * so we may have missed tracking something properly. now what? we need to retry the request, but without thinking that the container
		 * saw the missing creds. we will figure out if we know enough to do that.
		 */
		GUID containerGUID = EPRUtils.getGeniiContainerID(handler.getTargetEPR());
		String guidString = null; // records the guid of the container we're talking to, if we can find that out.
		if (containerGUID != null) {
			guidString = containerGUID.toString(true);
		}

		// we allow up to the max attempts to get the credentials right before we completely fail out.
		if (missingCredsAttemptCounter >= MAXIMUM_CREDENTIAL_RESENDING_ATTEMPTS) {
			_logger.warn("have already exhausted credential retry attempts; not trying again.  attempts at " + missingCredsAttemptCounter);
			return false;
		}
		if (!credOmittedMessage && !accessDeniedMessage) {
			// bail out, since we don't see either message we could work on.
			return false;
		}

		if (guidString == null) {
			// if we can't get the guid, we can't fix the records. probably we're hosed?
			_logger.error("failed to locate container GUID for target; cannot re-send credentials to it");
			return false; // fail out.
		}

		/*
		 * check a condition here, which is that we only react to access denied problems by retrying iff we guessed that the container
		 * supported credential streamlining but it probably does not. we don't just retry every access denied fault, since most are real, but
		 * if we are getting this when we guessed streamlining was supported, then it's very likely because this is an older container. so, if
		 * we did not guess, or it doesn't even claim to have cred streamlining support, then we don't treat this as a missing credential
		 * retryable situation. real missing creds should either be due to starting up with an unknown container, or due to problems with
		 * legitimately streamlining containers who have forgotten a credential.
		 */
		boolean okayToRetryCredSending = false;
		// if we see a streamlining failure message, we know it knows what streamlining is...
		if (credOmittedMessage) {
			okayToRetryCredSending = true;
		} else if (accessDeniedMessage && ClientCredentialTracker.doesContainerSupportStreamlining(guidString)
			&& !ClientCredentialTracker.didContainerActuallyAnswerStreamliningQuestion(guidString)) {
			/*
			 * we got the old access denied message... and we had said this supports streamlining... but the container hadn't confirmed
			 * this...
			 */
			if (_logger.isDebugEnabled()) {
				_logger.debug("we may have guessed wrong for container " + guidString
					+ " supporting streamlining and now will retry with this feature disabled");
			}
			ClientCredentialTracker.setContainerStreamliningSupport(guidString, false, false);
			okayToRetryCredSending = true;
		}

		if (okayToRetryCredSending) {
			if (_logger.isDebugEnabled())
				_logger.debug("container did not have a credential we referenced so will try sending again");

			// don't allow credentials to be remembered for the retry, if at all possible.
			ClientCredentialTracker.setContainerStreamliningSupport(guidString, false, false);

			boolean failedParsingRefs = false;

			if (credOmittedMessage) {
				if (missingCredsAttemptCounter == MAXIMUM_CREDENTIAL_RESENDING_ATTEMPTS) {
					/*
					 * this is our last try, so we just drop any memory of what the container thinks, since it keeps claiming missing creds.
					 */
					_logger.warn("up to maximum attempts to re-send missing creds; wiping memory of container history on client.");
					failedParsingRefs = true;
				} else {
					// try to parse out the credentials that were a problem.
					String justRefs = throwable.getMessage();
					int lastColon = justRefs.lastIndexOf(": ");
					if (lastColon < 0) {
						failedParsingRefs = true;
					}
					// truncate to just the portion we care about.
					justRefs = justRefs.substring(lastColon + 2);
					// parse out the credential references now that we have a simpler string.
					String[] refs = null;
					if (!failedParsingRefs) {
						refs = justRefs.split(" ");
						if ((refs == null) || (refs.length == 0)) {
							failedParsingRefs = true;
						}
					}

					if (!failedParsingRefs) {
						// hey, we made it, so now we can forget just those individual references.
						for (String ref : refs) {
							if (_logger.isDebugEnabled())
								_logger.debug("forgetting reference '" + ref + "' on container '" + guidString + "'");
							ClientCredentialTracker.forgetCredentialForContainer(guidString, ref);
						}
					}

					if (failedParsingRefs) {
						_logger.error(
							"failed to parse references out of credential omission message; will just dump container's entire history.");
					}
				}
			}

			// we only have to put our foot down if we didn't get a list of references, or if this container is old school.
			if (accessDeniedMessage || failedParsingRefs) {
				// toss out tracking info for this container.
				ClientCredentialTracker.flushContainerTracker(guidString);
			}

			// give it another try with same server etc.
			if (_logger.isDebugEnabled())
				_logger.debug("will retry RPC after updating credential tracker for container '" + guidString + "'");

			return true;
		}

		// if we felt able to re-send, we would have returned true before here. so we cannot re-send.
		return false;
	}

	/**
	 * Send the message. If it fails, then resolve a replica and try again.
	 * 
	 * Possible sequence of events: 1. Send message to first instance. Catch exception. 2. Ask resolver for second instance. 3. Send message
	 * to second instance. Catch exception. 4. Ask resolver for next instance. 5. There are no more instances, so resolve() throws an
	 * exception. Catch it. 6. Throw the failure that was reported by the first instance back in step 1. Discard the exceptions from resolve()
	 * and from all other instances.
	 */
	private Object resolveAndInvoke(ResolutionContext context, Method calledMethod, Object[] arguments, int timeout) throws Throwable
	{
		AxisClientInvocationHandler handler = null;

		boolean tryAgain = false; // if this flag is true, then at least one failure happened and we will retry with same handler.
		Throwable firstException = null; // the first exception that occurred during invocation.
		int missingCredsAttemptCounter = 0; // how many times have we had to retry sending more credentials?

		// keep looping until something good happens or something very bad happens.
		while (true) {
			long startTime = System.currentTimeMillis(); // invocation start time.
			try {
				/*
				 * if try again flag is true, it means we're giving the current handler another chance. if try again is false, then either
				 * we're here for the first time or we gave up on the last handler, but we will create a new handler.
				 */
				if (!tryAgain && (missingCredsAttemptCounter == 0)) {
					// come up with a client invocation handler for this context using the resolver.
					handler = resolve(context);
				} else {
					if (handler == null) {
						String msg = "serious logic error: there was no handler already created for the try again case";
						_logger.error(msg);
						throw new AxisFault(msg);
					}
				}
			} catch (Throwable throwable) {
				long duration = System.currentTimeMillis() - startTime;
				Throwable peeled = throwable;
				while (peeled instanceof AxisFault) {
					if (peeled.getCause() != null) {
						peeled = peeled.getCause();
					} else {
						break;
					}
				}
				_logger.error(
					"exception seen in resolution process after " + duration + "ms with exception " + peeled.getClass().getCanonicalName());

				if (firstException == null) {
					firstException = throwable;
				}
				/*
				 * it seems that if there's a failure during the resolution process, we blast it out right away. there is no trying again in
				 * this error case.
				 */
				throw firstException;
			}

			String host = handler.getTargetEPR().getAddress().get_value().getHost();
			int port = handler.getTargetEPR().getAddress().get_value().getPort();
			// the key is used for debugging output below.
			DeadHostChecker.HostKey key = new DeadHostChecker.HostKey(host, port);

			boolean alive = DeadHostChecker.evaluateHostAlive(host, port);
			if (!alive) {
				// this host is recorded as unhealthy, so let's give up in advance
				String msg = "Host " + key + " is known to be down, skipping";
				_logger.error(msg);
				throw new ConnectException(msg);
			}

			startTime = System.currentTimeMillis(); // invocation start time.
			try {
				Object toReturn = null;
				toReturn = handler.doInvoke(calledMethod, arguments, timeout);
				// if we got to here, the host is looking pretty healthy.
				DeadHostChecker.removeHostFromDeadPool(host, port);
				return toReturn;
			} catch (Throwable throwable) {
				long duration = System.currentTimeMillis() - startTime;
				if (throwable instanceof InvocationTargetException) {
					if (throwable.getCause() != null)
						throwable = throwable.getCause();
				}

				if (testForCredentialStreamliningIssues(throwable, missingCredsAttemptCounter, handler)) {
					// credential streamlining checking told us to try again. record that we are trying a re-sending here.
					missingCredsAttemptCounter++;
					if (_logger.isDebugEnabled())
						_logger.debug("will retry sending credentials with attempts now at " + missingCredsAttemptCounter);
					continue;
				} else {
					// have to reset the counter, since now we are saying we cannot try again and should re-resolve the handler and all that.
					missingCredsAttemptCounter = 0;
				}

				/*
				 * we failed to remedy the exception for our first two somewhat expected screw-ups. now we have to get serious and assume we
				 * actually had something bad happen. we want at least one breadcrumb in the log that shows the actual exception and how long
				 * the call took before the exception happened.
				 */
				if (_logger.isDebugEnabled()) {
					Throwable peeled = throwable;
					while (peeled instanceof AxisFault) {
						if (peeled.getCause() != null) {
							peeled = peeled.getCause();
						} else {
							break;
						}
					}
					_logger.debug("exception seen in invocation after " + duration + "ms of RPC call for " + calledMethod.getName()
						+ " with exception " + peeled.getClass().getCanonicalName());
				}

				if ((throwable instanceof TryAgainFaultType) && !tryAgain) {
					tryAgain = true;
					_logger.warn("trying operation again, since we received a try again fault, on method " + calledMethod.getName());
				} else {
					// report the error once we're sure we'll handle it here.

					DetailedLogger.detailed().warn(
						"resolveAndInvoke saw " + throwable.getClass().getCanonicalName() + " exception on method " + calledMethod.getName());

					if (firstException == null)
						firstException = throwable;
					tryAgain = false;

					/*
					 * Resetting the client cache as the original EPR holding container might be down, which will invalidate existing
					 * subscriptions and the notification broker.
					 */
					String methodName = calledMethod.getName();
					String throwmsg = throwable.getMessage() == null ? "Null message" : throwable.getMessage();
					Boolean securityException = throwmsg.contains(GenesisIIConstants.ACCESS_DENIED_SENTINEL);
					Boolean connectionProblem = throwmsg.contains("ConnectException");
					// Boolean SSLProblem = throwmsg.contains("SSLHandshakeException");

					/*
					 * ASG: Added July 14, 2015 to deal with dead hosts and not bother trying to talk to them. The timeouts kill us.
					 * 
					 * CAK: added a skip if the connection failure was super fast, since then we hope we can retry that type of failure just
					 * as swiftly. usually the connection failure mode stays consistent, i.e. a firewall is always keeping us the whole
					 * timeout period, whereas an unreachable host or down container can fail very fast.
					 */
					if ((throwable instanceof ConnectException || throwable instanceof UnknownHostException || connectionProblem)
						&& (duration > ClientProperties.getClientProperties().getMaximumAllowableConnectionPause())) {
						DeadHostChecker.addHostToDeadPool(host, port);
						String msg = "Communication failure for " + key;
						_logger.error(msg);
						throw new ConnectException(msg);
					} else {
						/*
						 * hmmm: why are we automatically considering the host is okay here? have we definitively enumerated all connection
						 * related exceptions above?
						 */
						DeadHostChecker.removeHostFromDeadPool(host, port);
					}
					// End of ASG deadHosts updates
					if (!(securityException || "destroy".equalsIgnoreCase(methodName)
						|| "createIndirectSubscriptions".equalsIgnoreCase(methodName) || "read".equalsIgnoreCase(methodName)
						|| "createNotificationBrokerWithForwardingPort".equalsIgnoreCase(methodName)
						|| "getMessages".equalsIgnoreCase(methodName) || "updateMode".equalsIgnoreCase(methodName))) {

						/*
						 * If the method is not the destroy method or any notification management method only then cache has been refreshed.
						 * Destroy method is ignored because otherwise there is a chance of cycle formation as the cache management system
						 * itself use WS-resources that are destroyed with a cache refresh and invocation of destroy on those resources can
						 * fail too. Meanwhile, notification management methods are ignored to avoid redundant cache refreshes.
						 */
						CacheManager.resetCachingForContainer(handler.getTargetEPR());
					}
				}
			}
		}
	}

	protected Object doInvoke(Method calledMethod, Object[] arguments, int timeout) throws Throwable
	{
		_lastEndpointInfo = null;
		MethodDescription methodDesc = new MethodDescription(calledMethod);
		Stub stubInstance = (Stub) _portMethods.get(methodDesc);

		try {
			configureSecurity(stubInstance);
		} catch (Throwable e) {
			_logger.error("failed to configure security", e);
			throw e;
		}

		if (_outAttachments != null) {
			if (_attachmentType == AttachmentType.DIME)
				stubInstance._setProperty(Call.ATTACHMENT_ENCAPSULATION_FORMAT, Call.ATTACHMENT_ENCAPSULATION_FORMAT_DIME);
			else
				stubInstance._setProperty(Call.ATTACHMENT_ENCAPSULATION_FORMAT, Call.ATTACHMENT_ENCAPSULATION_FORMAT_MTOM);

			for (GeniiAttachment outAttachment : _outAttachments) {
				ByteArrayDataSource ds = new ByteArrayDataSource(outAttachment.getData(), "application/octet-stream");
				String name = outAttachment.getName();
				if (name != null)
					ds.setName(name);
				stubInstance.addAttachment(new DataHandler(ds));
			}
		}
		stubInstance.setTimeout(timeout);
		/*
		 * Set calling context so that the socket factory has access to it.
		 */
		if (_logger.isTraceEnabled())
			_logger.trace(String.format("Starting an outcall for %s on thread [%x]%s.", calledMethod.getName(),
				Thread.currentThread().getId(), Thread.currentThread()));
		long start = System.currentTimeMillis();
		VcgrSslSocketFactory.threadCallingContext.set(_callContext);
		// perform the actual method invocation over RPC.
		Object ret = calledMethod.invoke(stubInstance, arguments);
		VcgrSslSocketFactory.threadCallingContext.set(null);
		if (_logger.isTraceEnabled())
			_logger.trace(String.format("Finished an outcall for %s on thread [%x]%s (duration %d ms).", calledMethod.getName(),
				Thread.currentThread().getId(), Thread.currentThread(), System.currentTimeMillis() - start));
		if (_logger.isDebugEnabled())
			_logger.debug(String.format("Outcall for '%s' took %d ms.", calledMethod.getName(), System.currentTimeMillis() - start));

		Object[] inAttachments = stubInstance.getAttachments();
		if (inAttachments != null)
			setInAttachments(inAttachments);

		boolean isGeniiEndpoint = false;
		boolean supportsStreamlining = false;
		Version endpointVersion = null;

		GUID containerGUID = EPRUtils.getGeniiContainerID(_epr);
		String guidString = "UNKNOWN";
		if (containerGUID != null)
			guidString = containerGUID.toString(true);

		for (SOAPHeaderElement elem : stubInstance.getResponseHeaders()) {
			QName name = elem.getQName();
			if (name.equals(GeniiSOAPHeaderConstants.GENII_ENDPOINT_QNAME)) {
				org.w3c.dom.Node n = elem.getFirstChild();
				if (n != null) {
					String text = n.getNodeValue();
					if (text != null && text.equalsIgnoreCase("true"))
						isGeniiEndpoint = true;
				}
			} else if (name.equals(GeniiSOAPHeaderConstants.GENII_ENDPOINT_VERSION_QNAME)) {
				org.w3c.dom.Node n = elem.getFirstChild();
				if (n != null) {
					String text = n.getNodeValue();
					if (text != null) {
						try {
							endpointVersion = new Version(text);
						} catch (Throwable cause) {
							_logger.warn("Unable to parse version from soap header.", cause);
						}
					}
				}
			} else if (name.equals(GeniiSOAPHeaderConstants.GENII_SUPPORTS_CREDENTIAL_STREAMLINING_QNAME)) {
				// found our credential streamlining sentinel header. check whether it's set to true or not.
				org.w3c.dom.Node n = elem.getFirstChild();
				if (n != null) {
					String text = n.getNodeValue();
					if (text != null && text.equalsIgnoreCase("true")) {
						supportsStreamlining = true;
					}
				}
			} else if (name.equals(NotificationBrokerConstants.MESSAGE_INDEX_QNAME)) {
				EndpointReferenceType target = getTargetEPR();
				int messageIndex = Integer.parseInt(elem.getValue());
				NotificationMessageIndexProcessor.processMessageIndexValue(target, messageIndex);
			}
		}

		if (supportsStreamlining) {
			if (CredentialCache.SHOW_CREDENTIAL_STREAMLINING_ACTIONS && _logger.isDebugEnabled())
				_logger.debug("container supports credential streamlining: " + guidString);
			if (containerGUID != null) {
				ClientCredentialTracker.setContainerStreamliningSupport(guidString, true, true);
			}
		} else {
			if (CredentialCache.SHOW_CREDENTIAL_STREAMLINING_ACTIONS && _logger.isDebugEnabled())
				_logger.debug("container does not support credential streamlining: " + guidString);
			if (containerGUID != null) {
				ClientCredentialTracker.setContainerStreamliningSupport(guidString, false, true);
			}
		}

		_lastEndpointInfo = new GenesisIIEndpointInformation(isGeniiEndpoint, endpointVersion, supportsStreamlining);
		return ret;
	}

	private void setInAttachments(Object[] inAttachments) throws IOException, SOAPException
	{
		Collection<GeniiAttachment> attachmentsList = new LinkedList<GeniiAttachment>();
		for (Object nextAttachment : inAttachments) {
			if (nextAttachment instanceof AttachmentPart) {
				AttachmentPart part = (AttachmentPart) nextAttachment;
				attachmentsList.add(new GeniiAttachment(GeniiAttachment.extractData(part)));
			} else {
				_logger.warn("Received an attachment type that I don't know how to deal with.");
			}
		}

		// set inbound attachments all the way up the stack of invocation handlers
		AxisClientInvocationHandler nextHandler = this;
		while (nextHandler != null) {
			if (attachmentsList.size() > 0)
				nextHandler._inAttachments = attachmentsList;
			else
				nextHandler._inAttachments = null;
			nextHandler = nextHandler._parentHandler;
		}
	}

	/*
	 * Try to resolve EPR (based on resolution context) and return a new invocation handler for next invocation attempt. If resolution fails,
	 * throw exception.
	 * 
	 * WARNING: Contents of ResolutionContext may be changed during this call.
	 */
	protected AxisClientInvocationHandler resolve(ResolutionContext context) throws NameResolutionFailedException
	{
		EndpointReferenceType originalEPR = context.getOriginalEPR();
		if ((!context.triedOriginalEPR()) && (!EPRUtils.isUnboundEPR(originalEPR))) {
			context.setTriedOriginalEPR();
			return this;
		}
		if (!context.rebindAllowed()) {
			throw new NameResolutionFailedException();
		}
		try {
			EndpointReferenceType resolvedEPR = context.resolve();
			if (resolvedEPR != null) {
				AxisClientInvocationHandler newHandler = cloneHandlerForNewEPR(resolvedEPR);
				if (newHandler != null) {
					return newHandler;
				}
			}
		} catch (RemoteException exception) {
			throw new NameResolutionFailedException(exception);
		}
		throw new NameResolutionFailedException();
	}

	public EndpointReferenceType getTargetEPR()
	{
		return _epr;
	}

	public void setOutAttachments(Collection<GeniiAttachment> attachments, AttachmentType attachmentType)
	{
		if (_parentHandler != null) {
			if (_logger.isDebugEnabled())
				_logger.warn("Tried to set outbound attachments on cloned AxisClientInvocationHandler.");
		}
		_outAttachments = attachments;
		_attachmentType = attachmentType;
	}

	public void setTimeout(int timeoutMillis)
	{
		_timeout = new Integer(timeoutMillis);
	}

	public Collection<GeniiAttachment> getInAttachments()
	{
		if (_parentHandler != null) {
			_logger.warn("Tried to get inbound attachments on cloned AxisClientInvocationHandler.");
		}
		Collection<GeniiAttachment> res = _inAttachments;
		_inAttachments = null;

		return res;
	}

	public GenesisIIEndpointInformation getLastEndpointInformation()
	{
		return _lastEndpointInfo;
	}
}
