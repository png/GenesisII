package edu.virginia.vcgr.genii.container.sync;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.UndeclaredThrowableException;
import java.rmi.RemoteException;
import java.util.List;

import javax.xml.namespace.QName;

import org.apache.axis.message.MessageElement;
import org.apache.axis.types.URI;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.oasis_open.docs.wsrf.r_2.ResourceUnavailableFaultType;
import org.oasis_open.docs.wsrf.r_2.ResourceUnknownFaultType;
import org.oasis_open.docs.wsrf.rp_2.GetResourcePropertyResponse;
import org.oasis_open.docs.wsrf.rp_2.InvalidResourcePropertyQNameFaultType;
import org.ws.addressing.EndpointReferenceType;

import edu.virginia.vcgr.genii.client.comm.ClientUtils;
import edu.virginia.vcgr.genii.client.naming.EPRUtils;
import edu.virginia.vcgr.genii.client.naming.ResolverDescription;
import edu.virginia.vcgr.genii.client.naming.ResolverUtils;
import edu.virginia.vcgr.genii.client.naming.WSName;
import edu.virginia.vcgr.genii.client.notification.NotificationConstants;
import edu.virginia.vcgr.genii.client.resource.ResourceException;
import edu.virginia.vcgr.genii.common.GeniiCommon;
import edu.virginia.vcgr.genii.container.resolver.GeniiResolverServiceImpl;
import edu.virginia.vcgr.genii.container.resource.IResource;
import edu.virginia.vcgr.genii.resolver.CountRequestType;
import edu.virginia.vcgr.genii.resolver.ExtResolveRequestType;
import edu.virginia.vcgr.genii.resolver.GeniiResolverPortType;

public class VersionedResourceUtils
{
	static private Log _logger = LogFactory.getLog(VersionedResourceUtils.class);
	
	/**
	 * Setup the state of a new resource before invoking ReplicationThread.
	 */
	public static void initializeReplica(IResource resource, EndpointReferenceType primaryEPR, int targetID)
		throws ResourceException
	{
		resource.setProperty(SyncProperty.PRIMARY_EPR_PROP_NAME, EPRUtils.toBytes(primaryEPR));
		resource.setProperty(SyncProperty.ERROR_STATE_PROP_NAME, "unsubscribed");
		if (targetID > 0)
			resource.setProperty(SyncProperty.TARGET_ID_PROP_NAME, new Integer(targetID));
		resource.commit();
	}

	/**
	 * Ask the resolver for the complete list of physical resources (with targetIDs)
	 * that make up this logical resource.
	 */
	public static EndpointReferenceType[] getTargetEPRs(WSName wsname)
		throws RemoteException
	{
		URI targetEPI = wsname.getEndpointIdentifier();
		List<ResolverDescription> resolvers = ResolverUtils.getResolvers(wsname);
		if ((targetEPI == null) || (resolvers == null))
			return null;
		int[] targetIDList = null;
		GeniiResolverPortType proxy = null;
		for (ResolverDescription resolver : resolvers)
		{
			if (resolver.getType() != ResolverDescription.ResolverType.EPI_RESOLVER)
				continue;
			proxy = ClientUtils.createProxy(GeniiResolverPortType.class, resolver.getEPR());
			try
			{
				targetIDList = proxy.getEndpointCount(new CountRequestType(targetEPI));
				break;
			}
			catch (Exception exception)
			{
				_logger.debug("getTargetEPRs: " + exception);
			}
		}
		if (targetIDList == null)
			return null;
		int targetID = targetIDList[targetIDList.length-1];
		EndpointReferenceType[] replicaList = new EndpointReferenceType[targetID+1];
		for (int idx = 0; idx < targetIDList.length; idx++)
		{
			targetID = targetIDList[idx];
			MessageElement param = new MessageElement(
					GeniiResolverServiceImpl.TARGET_ID_PARAMETER, Integer.toString(targetID));
			MessageElement[] params = new MessageElement[]{ param };
			ExtResolveRequestType request = new ExtResolveRequestType(targetEPI, params);
			replicaList[targetID] = proxy.extResolveEPI(request);
		}
		return replicaList;
	}
	
	/**
	 * Send a "getResourceProperty" request for a property that may or may not be defined.
	 * If the property is not defined, do not throw an exception.  Simply return null.
	 */
	static public MessageElement getResourceProperty(GeniiCommon common, QName property)
		throws RemoteException, ResourceUnknownFaultType, ResourceUnavailableFaultType
	{
		GetResourcePropertyResponse rpResponse;
		try
		{
			rpResponse = common.getResourceProperty(property);
		}
		catch (InvalidResourcePropertyQNameFaultType fault)
		{
			_logger.debug("property " + property + " invalid");
			return null;
		}
		catch (UndeclaredThrowableException ute)
		{
			_logger.debug("Fault thrown through UndeclaredThrowableException");
			Throwable fault = ute.getCause();
			if (fault instanceof InvocationTargetException)
			{
				_logger.debug("Fault thrown through InvocationTargetException");
				fault = fault.getCause();
			}
			if (fault instanceof InvalidResourcePropertyQNameFaultType)
			{
				_logger.debug("property " + property + " invalid");
				return null;
			}
			if (fault instanceof RemoteException)
			{
				throw (RemoteException) fault;
			}
			throw ute;
		}
		MessageElement[] valueArr = rpResponse.get_any();
		if ((valueArr == null) || (valueArr.length == 0))
			return null;
		return valueArr[0];
	}
	
	/**
	 * Send a request to this resource to download its version vector.
	 */
	public static VersionVector getVersionVector(EndpointReferenceType epr)
		throws RemoteException
	{
		GeniiCommon common = ClientUtils.createProxy(GeniiCommon.class, epr);
		MessageElement response = getResourceProperty(common, SyncProperty.VERSION_VECTOR_QNAME);
		if ((response == null) || (response.getValue() == null))
			return null;
		return VersionVector.fromString(response.getValue());
	}
	
	/**
	 * Increment the version of this resource in the VersionVector in the resource properties.
	 */
	public static VersionVector incrementResourceVersion(IResource resource)
		throws ResourceException
	{
		VersionVector vector = (VersionVector) resource.getProperty(SyncProperty.VERSION_VECTOR_PROP_NAME);
		if (vector == null)
		{
			vector = new VersionVector();
			vector.setVersion(0, 1);
		}
		else
		{
			vector.setVersion(vector.getLocalID(), vector.getLocalVersion()+1);
		}
		resource.setProperty(SyncProperty.VERSION_VECTOR_PROP_NAME, vector);
		resource.commit();
		return vector;
	}
	
	/**
	 * When a versioned resource receives an update message, the resource should call this function
	 * to determine whether it should apply the modification.
	 */
	public static MessageFlags validateNotification(IResource resource,
			VersionVector localVector, VersionVector remoteVector)
		throws ResourceException
	{
		MessageFlags flags = new MessageFlags();
		if (resource.getProperty(SyncProperty.ERROR_STATE_PROP_NAME) != null)
		{
			_logger.debug("validateNotification: resource is in error state");
			flags.status = NotificationConstants.FAIL;
			return flags;
		}
		int remoteUid = remoteVector.getLocalID();
		int remoteVersion = remoteVector.getLocalVersion();
		int localUid = 0;
		int localVersion = 0;
		if (localVector != null)
		{
			localUid = localVector.getLocalID();
			localVersion = localVector.getVersion(remoteUid);
		}
		if (localVersion >= remoteVersion)
		{
			_logger.debug("validateNotification: have " + localVersion + " received " + remoteVersion);
			flags.duplicate = true;
			flags.status = NotificationConstants.OK;
			return flags;
		}
		if (localVersion+1 < remoteVersion)
		{
			_logger.debug("validateNotification: jump from " + localVersion + " to " + remoteVersion);
			flags.outOfOrder = true;
			flags.status = NotificationConstants.TRYAGAIN;
			return flags;
		}
		remoteVersion = remoteVector.getVersion(localUid);
		localVersion = (localVector == null ? 0 : localVector.getLocalVersion());
		if (remoteVersion > localVersion)
		{
			_logger.debug("validateNotification: at " + localVersion + " received " + remoteVersion);
			flags.status = NotificationConstants.FAIL;
			return flags;
		}
		for (VersionItem item : remoteVector.vector)
		{
			if (item.uid == localUid || item.uid == remoteUid)
				continue;
			int lv = (localVector == null ? 0 : localVector.getVersion(item.uid));
			if (item.version > lv)
			{
				_logger.debug("validateNotification: uid " + item.uid +
						" have " + lv + " need " + item.version);
				flags.missing3rdParty = true;
				flags.status = NotificationConstants.TRYAGAIN;
				return flags;
			}
		}
		if (localVersion > remoteVersion)
		{
			_logger.debug("validateNotification: local " + localVersion + " remote " + remoteVersion);
			flags.conflict = true;
			if (localUid < remoteUid)
			{
				_logger.debug("validateNotification: will replay");
				flags.replay = true;
			}
		}
		return flags;
	}
	
	/**
	 * After processing an update message, update the local VersionVector with the new remote version number,
	 * and store the updated VersionVector as a resource property.
	 */
	public static void updateVersionVector(IResource resource,
			VersionVector localVector, VersionVector remoteVector)
		throws ResourceException
	{
		if (localVector == null)
		{
			localVector = new VersionVector();
			localVector.setVersion(0, 0);
		}
		localVector.setVersion(remoteVector.getLocalID(), remoteVector.getLocalVersion());
		resource.setProperty(SyncProperty.VERSION_VECTOR_PROP_NAME, localVector);
		resource.commit();
		_logger.debug("updateVersionVector: vv=" + localVector);
	}
	
	/**
	 * Replicated resources should call this before they are destroyed.
	 * If the resource should send an update message indicating that it has been destroyed,
	 * then this returns the VersionVector for the update message.
	 * Otherwise, it returns null.
	 * 
	 * Also, check if the resource has been "unlinked" from the other replicas,
	 * meaning that they should be unaffected (other then destroying the unlinked
	 * replica's subscription).
	 */
	public static DestroyFlags preDestroy(IResource resource)
		throws ResourceException
	{
		if (resource.getProperty(SyncProperty.IS_DESTROYED_PROP_NAME) != null)
			return null;
		DestroyFlags flags = new DestroyFlags();
		flags.vvr = incrementResourceVersion(resource);
		if (resource.getProperty(SyncProperty.UNLINKED_REPLICA_PROP_NAME) != null)
			flags.isUnlinked = true;
		return flags;
	}
	
	/**
	 * Given a local resource and a remote resource:
	 * The remote resource was subscribed to the local resource.
	 * The remote resource has been destroyed.
	 * Search the local subscription database for the subscription from the remote resource,
	 * and destroy the subscription.
	 */
	public static void destroySubscription(IResource publisher, EndpointReferenceType consumerEPR)
	{
		// TODO - implement destroySubscription()
	}
}
