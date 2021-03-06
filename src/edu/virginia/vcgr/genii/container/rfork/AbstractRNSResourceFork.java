package edu.virginia.vcgr.genii.container.rfork;

import java.io.IOException;

import org.oasis_open.docs.wsrf.r_2.ResourceUnknownFaultType;
import org.ws.addressing.EndpointReferenceType;

import edu.virginia.vcgr.genii.client.resource.ResourceException;

import edu.virginia.vcgr.genii.container.rfork.iterator.InMemoryIterableFork;
import edu.virginia.vcgr.genii.container.rns.InternalEntry;

public abstract class AbstractRNSResourceFork extends AbstractResourceFork implements RNSResourceFork
{
	protected AbstractRNSResourceFork(ResourceForkService service, String forkPath)
	{
		super(service, forkPath);
	}

	protected InternalEntry createInternalEntry(EndpointReferenceType exemplarEPR, String entryName, ResourceForkInformation rif,
		boolean isExistent) throws ResourceUnknownFaultType, ResourceException
	{
		return new InternalEntry(entryName, getService().createForkEPR(formForkPath(entryName), rif), null, isExistent);
	}

	protected InternalEntry createInternalEntry(EndpointReferenceType exemplarEPR, String entryName, ResourceForkInformation rif)
		throws ResourceUnknownFaultType, ResourceException
	{
		return new InternalEntry(entryName, getService().createForkEPR(formForkPath(entryName), rif), null, rif, true);
	}

	protected String formForkPath(String entryName)
	{
		String path = getForkPath();
		if (path.endsWith("/"))
			return path + entryName;
		return path + "/" + entryName;
	}

	@Override
	public boolean isInMemoryIterable() throws IOException
	{
		return false;
	}

	@Override
	public InMemoryIterableFork getInMemoryIterableFork() throws IOException
	{
		return null;
	}

}