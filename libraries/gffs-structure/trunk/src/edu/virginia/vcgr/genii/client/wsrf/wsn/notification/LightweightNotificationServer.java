package edu.virginia.vcgr.genii.client.wsrf.wsn.notification;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.xml.soap.MessageFactory;
import javax.xml.soap.SOAPBody;
import javax.xml.soap.SOAPException;
import javax.xml.soap.SOAPMessage;

import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.eclipse.jetty.server.ssl.SslSelectChannelConnector;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.morgan.util.io.StreamUtils;
import org.oasis_open.wsn.base.Notify;
import org.oasis_open.wsn.base.SubscribeResponse;
import org.w3c.dom.Element;
import org.ws.addressing.AttributedURIType;
import org.ws.addressing.EndpointReferenceType;

import edu.virginia.vcgr.genii.client.cmd.ToolException;
import edu.virginia.vcgr.genii.client.comm.ClientUtils;
import edu.virginia.vcgr.genii.client.configuration.ConfiguredHostname;
import edu.virginia.vcgr.genii.client.ser.ObjectDeserializer;
import edu.virginia.vcgr.genii.client.wsrf.wsn.AdditionalUserData;
import edu.virginia.vcgr.genii.client.wsrf.wsn.DefaultNotificationMultiplexer;
import edu.virginia.vcgr.genii.client.wsrf.wsn.NotificationHandler;
import edu.virginia.vcgr.genii.client.wsrf.wsn.NotificationMessageContents;
import edu.virginia.vcgr.genii.client.wsrf.wsn.NotificationMultiplexer;
import edu.virginia.vcgr.genii.client.wsrf.wsn.NotificationRegistration;
import edu.virginia.vcgr.genii.client.wsrf.wsn.subscribe.AbstractSubscription;
import edu.virginia.vcgr.genii.client.wsrf.wsn.subscribe.AbstractSubscriptionFactory;
import edu.virginia.vcgr.genii.client.wsrf.wsn.subscribe.SubscribeException;
import edu.virginia.vcgr.genii.client.wsrf.wsn.subscribe.SubscribeRequest;
import edu.virginia.vcgr.genii.client.wsrf.wsn.subscribe.Subscription;
import edu.virginia.vcgr.genii.client.wsrf.wsn.subscribe.TerminationTimeType;
import edu.virginia.vcgr.genii.client.wsrf.wsn.subscribe.policy.SubscriptionPolicy;
import edu.virginia.vcgr.genii.client.wsrf.wsn.topic.TopicQueryExpression;
import edu.virginia.vcgr.genii.common.GeniiCommon;

public class LightweightNotificationServer
{
	static private enum Protocols {
		http,
		https;
	}

	static final private String URL_PATTERN = "%1$s://%2$s:%3$d/";

	static private SelectChannelConnector createSocketConnector(Integer port)
	{
		SelectChannelConnector listener = new SelectChannelConnector();
		if (port != null)
			listener.setPort(port);

		// temp to see if conn refused goes away.
		// did not help: listener.setAcceptors(16);

		return listener;
	}

	static private SelectChannelConnector createSslSocketConnector(Integer port, String keystore, String keystoreType, String password,
		String keyPassword)
	{
		SslContextFactory factory = new SslContextFactory();
		factory.setKeyStorePath(keystore);
		factory.setKeyStoreType(keystoreType);
		factory.setKeyStorePassword(keyPassword);
		factory.setKeyManagerPassword(password);

		SelectChannelConnector listener = new SslSelectChannelConnector(factory);

		if (port != null) {
			listener.setPort(port);
		}

		return listener;
	}

	private class NotificationJettyHandler extends AbstractHandler
	{
		@Override
		public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response)
			throws IOException, ServletException
		{
			InputStream in = null;

			try {
				in = request.getInputStream();
				SOAPMessage msg = MessageFactory.newInstance().createMessage(null, in);
				// drop the stream as soon as we can.
				StreamUtils.close(in);
				SOAPBody body = msg.getSOAPBody();
				Element notifyElement = (Element) body.getFirstChild();
				Notify notify = (Notify) ObjectDeserializer.toObject(notifyElement, Notify.class);
				NotificationHelper.notify(notify, _multiplexer);
			} catch (SOAPException e) {
				throw new IOException("Unable to parse notification message.", e);
			} finally {
				StreamUtils.close(in);
			}
		}
	}

	private class LightweightSubscriptionImpl extends AbstractSubscription implements LightweightSubscription
	{
		private TopicQueryExpression _queryExpression;

		LightweightSubscriptionImpl(TopicQueryExpression queryExpression, SubscribeResponse response)
		{
			super(response);
		}

		@Override
		public void cancel()
		{
			synchronized (_subscriptions) {
				_subscriptions.remove(this);
			}

			super.cancel();
		}

		@Override
		final public <ContentsType extends NotificationMessageContents> NotificationRegistration
			registerNotificationHandler(NotificationHandler<ContentsType> handler)
		{
			return _multiplexer.registerNotificationHandler(_queryExpression, handler);
		}
	}

	private NotificationMultiplexer _multiplexer = new DefaultNotificationMultiplexer();

	private Protocols _protocol;
	private Server _httpServer;

	private Set<LightweightSubscription> _subscriptions = new HashSet<LightweightSubscription>();

	public void setMultiplexer(NotificationMultiplexer multiplexer)
	{
		this._multiplexer = multiplexer;
	}

	public EndpointReferenceType getEPR() throws IOException
	{
		if (!_httpServer.isStarted())
			throw new IOException("Server not started!");

		return new EndpointReferenceType(new AttributedURIType(String.format(URL_PATTERN, _protocol,
			ConfiguredHostname.getMostGlobal().getCanonicalHostName(), _httpServer.getConnectors()[0].getLocalPort())), null, null, null);
	}

	private ContextHandler createContext()
	{
		ContextHandler handler = new ContextHandler();
		handler.setContextPath("/");
		handler.setHandler(new NotificationJettyHandler());
		return handler;
	}

	private LightweightNotificationServer(SelectChannelConnector listener)
	{
		_protocol = (listener instanceof SslSelectChannelConnector) ? Protocols.https : Protocols.http;

		_httpServer = new Server();

		_httpServer.addConnector(listener);
		_httpServer.setHandler(createContext());
	}

	private LightweightNotificationServer(Integer port)
	{
		this(createSocketConnector(port));
	}

	private LightweightNotificationServer(Integer port, String keystore, String keystoreType, String password, String keyPassword)
	{
		this(createSslSocketConnector(port, keystore, keystoreType, password, keyPassword));
	}

	@Override
	protected void finalize() throws Throwable
	{
		stop();
	}

	final public void start() throws ToolException
	{
		try {
			_httpServer.start();
		} catch (Exception e) {
			throw new ToolException("failure to start https server: " + e.getLocalizedMessage(), e);
		}
	}

	final public void stop() throws ToolException
	{
		if (_httpServer.isStarted()) {
			try {
				_httpServer.stop();
			} catch (Exception e) {
				throw new ToolException("failure stopping https server: " + e.getLocalizedMessage(), e);
			}
			synchronized (_subscriptions) {
				for (Subscription subscription : _subscriptions)
					subscription.cancel();
			}
		}
	}

	final public SubscribeRequest createSubscribeRequest(TopicQueryExpression topicFilter, TerminationTimeType terminationTime,
		AdditionalUserData additionalUserData, SubscriptionPolicy... policies) throws IOException
	{
		return AbstractSubscriptionFactory.createRequest(getEPR(), topicFilter, terminationTime, additionalUserData, policies);
	}

	final public LightweightSubscription subscribe(EndpointReferenceType publisher, TopicQueryExpression topicFilter,
		TerminationTimeType terminationTime, AdditionalUserData additionalUserData, SubscriptionPolicy... policies) throws SubscribeException
	{
		try {
			GeniiCommon common = ClientUtils.createProxy(GeniiCommon.class, publisher);
			return new LightweightSubscriptionImpl(topicFilter,
				common.subscribe(createSubscribeRequest(topicFilter, terminationTime, additionalUserData, policies).asRequestType()));
		} catch (IOException e) {
			throw new SubscribeException("Unable to subscribe consumer to publisher!", e);
		}
	}

	final public <ContentsType extends NotificationMessageContents> NotificationRegistration
		registerNotificationHandler(TopicQueryExpression topicFilter, NotificationHandler<ContentsType> handler)
	{
		return _multiplexer.registerNotificationHandler(topicFilter, handler);
	}

	static public LightweightNotificationServer createStandardServer()
	{
		return new LightweightNotificationServer((Integer) null);
	}

	static public LightweightNotificationServer createStandardServer(int port)
	{
		return new LightweightNotificationServer(port);
	}

	static public LightweightNotificationServer createSslServer(String keystoreLocation, String keystoreType, String keystorePassword,
		String keyPassword)
	{
		return new LightweightNotificationServer(null, keystoreLocation, keystoreType, keystorePassword, keyPassword);
	}

	static public LightweightNotificationServer createSslServer(int port, String keystoreLocation, String keystoreType,
		String keystorePassword, String keyPassword)
	{
		return new LightweightNotificationServer(port, keystoreLocation, keystoreType, keystorePassword, keyPassword);
	}
}
