package edu.virginia.vcgr.genii.container.q2;

import java.sql.Connection;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.ggf.bes.factory.GetActivityStatusResponseType;
import org.ggf.bes.factory.GetActivityStatusesType;
import org.ws.addressing.EndpointReferenceType;

import edu.virginia.vcgr.genii.bes.GeniiBESPortType;
import edu.virginia.vcgr.genii.client.bes.ActivityState;
import edu.virginia.vcgr.genii.client.comm.ClientUtils;
import edu.virginia.vcgr.genii.client.postlog.JobEvent;
import edu.virginia.vcgr.genii.client.postlog.PostTargets;
import edu.virginia.vcgr.genii.container.db.DatabaseConnectionPool;

/**
 * The asynchronous worker that actually makes the outcall to the bes container
 * to see if a job has finished or failed.
 * 
 * @author mmm2a
 */
public class JobUpdateWorker implements OutcallHandler
{
	static private Log _logger = LogFactory.getLog(JobUpdateWorker.class);
	
	private IBESPortTypeResolver _clientStubResolver;
	private JobCommunicationInfo _jobInfo;
	private JobManager _jobManager;
	private DatabaseConnectionPool _connectionPool;
	private IJobEndpointResolver _jobEndpointResolver;
	private JobData _data;
	
	public JobUpdateWorker(JobManager jobManager,
		IBESPortTypeResolver clientStubResolver,
		IJobEndpointResolver jobEndpointResolver,
		DatabaseConnectionPool connectionPool,
		JobCommunicationInfo jobInfo, JobData data)
	{
		_jobManager = jobManager;
		_clientStubResolver = clientStubResolver;
		_jobInfo = jobInfo;
		_connectionPool = connectionPool;
		_jobEndpointResolver = jobEndpointResolver;
		_data = data;
	}
	
	public boolean equals(JobUpdateWorker other)
	{
		return (_data.getJobID() == other._data.getJobID());
	}
	
	@Override
	public boolean equals(OutcallHandler other)
	{
		if (other instanceof JobUpdateWorker)
			return equals((JobUpdateWorker)other);
		
		return false;
	}
	
	@Override
	public boolean equals(Object other)
	{
		if (other instanceof JobUpdateWorker)
			return equals((JobUpdateWorker)other);
		
		return false;
	}
	
	public void run()
	{
		Connection connection = null;
		
		try
		{
			_logger.debug("Checking status of job " + _jobInfo.getJobID());
	
			/* Get a connection from the connection pool and then ask the 
			 * resolvers to get the memory "large" information from the
			 * database.
			 */
			connection = _connectionPool.acquire();
			EndpointReferenceType jobEndpoint = _jobEndpointResolver.getJobEndpoint(
				connection, _jobInfo.getJobID());
			GeniiBESPortType clientStub = _clientStubResolver.createClientStub(
				connection, _jobInfo.getBESID());
			ClientUtils.setTimeout(clientStub, 120 * 1000);
			
			if (jobEndpoint == null)
			{
				// Another thread has removed this from the database.
				_logger.debug(
					"Asked to check status on a job which is " +
					"no longer running according to the database.");
				return;
			}
			
			String oldAction = _data.setJobAction("Checking");
			if (oldAction != null)
			{
				_logger.error("Attempting to check job status, found that " +
					"we are in the process of doing action:  " + oldAction);
				return;
			}
			
			GetActivityStatusResponseType []activityStatuses;
			try
			{
				/* call the BES container to get the activity's status. */
				activityStatuses 
					= clientStub.getActivityStatuses(new GetActivityStatusesType(
						new EndpointReferenceType[] { jobEndpoint }, null)).getResponse();
			}
			finally
			{
				_data.clearJobAction();
			}
			
			/* If we didn't get one back, then there was 
			 * a weird internal error. */
			if (activityStatuses == null || activityStatuses.length != 1)
			{
				_logger.error("Unable to get activity status for job " 
					+ _jobInfo.getJobID());
			} else
			{
				/* We have it's status, convert it to a more reasonable data
				 * type (not the auto generated from WSDL one which is
				 * worthless).
				 */
				ActivityState state = new ActivityState(
					activityStatuses[0].getActivityStatus());
				if (state.isFailedState())
				{
					/* If the job failed in the BES, fail it in the queue */
					if (!_jobManager.failJob(connection, _jobInfo.getJobID(), 
						true, false))
						PostTargets.poster().post(
							JobEvent.jobFailed(null, 
								Long.toString(_jobInfo.getJobID())));
				} else if (state.isCancelledState())
				{
					/* If the job was cancelled, then finish it here */
					_jobManager.failJob(connection, _jobInfo.getJobID(), false, false);
					PostTargets.poster().post(JobEvent.jobKilled(null,
						Long.toString(_jobInfo.getJobID())));
				} else if (state.isFinishedState())
				{
					/* If the job finished on the bes, finish it here */
					_jobManager.finishJob(connection, _jobInfo.getJobID());
					PostTargets.poster().post(JobEvent.jobFinished(null,
						Long.toString(_jobInfo.getJobID())));
				}
			}
		}
		catch (Throwable cause)
		{
			_logger.warn("Unable to update job status for job " 
				+ _jobInfo.getJobID(), cause);
		}
		finally
		{
			_connectionPool.release(connection);
		}
	}
}
