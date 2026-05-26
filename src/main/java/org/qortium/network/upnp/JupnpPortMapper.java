package org.qortium.network.upnp;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jupnp.UpnpService;
import org.jupnp.UpnpServiceImpl;
import org.jupnp.controlpoint.ActionCallback;
import org.jupnp.model.action.ActionArgumentValue;
import org.jupnp.model.action.ActionInvocation;
import org.jupnp.model.message.UpnpResponse;
import org.jupnp.model.message.header.UDAServiceTypeHeader;
import org.jupnp.model.meta.Action;
import org.jupnp.model.meta.Device;
import org.jupnp.model.meta.RemoteDevice;
import org.jupnp.model.meta.RemoteService;
import org.jupnp.model.meta.Service;
import org.jupnp.model.types.UDAServiceType;

import java.net.InetAddress;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class JupnpPortMapper implements PortMapper {

	private static final Logger LOGGER = LogManager.getLogger(JupnpPortMapper.class);

	private static final int DISCOVERY_WAIT_SECONDS = 3;
	private static final int ACTION_WAIT_SECONDS = 10;
	private static final String PROTOCOL_TCP = "TCP";

	private static final UDAServiceType[] WAN_SERVICE_TYPES = new UDAServiceType[] {
			new UDAServiceType("WANIPConnection", 2),
			new UDAServiceType("WANIPConnection", 1),
			new UDAServiceType("WANPPPConnection", 1)
	};

	private static final JupnpPortMapper INSTANCE = new JupnpPortMapper();

	private final Object lock = new Object();
	private final Set<Integer> mappedTcpPorts = new LinkedHashSet<>();

	private UpnpService upnpService;

	public static JupnpPortMapper getInstance() {
		return INSTANCE;
	}

	private JupnpPortMapper() {
	}

	@Override
	public PortMappingResult openTcpPort(int port, String description) {
		try {
			RemoteService service = this.findWanConnectionService();
			if (service == null)
				return PortMappingResult.notMapped();

			InetAddress internalAddress = this.getInternalAddress(service);
			if (internalAddress == null)
				return PortMappingResult.notMapped();

			if (!this.addPortMapping(service, port, internalAddress.getHostAddress(), description))
				return PortMappingResult.notMapped();

			synchronized (this.lock) {
				this.mappedTcpPorts.add(port);
			}

			return PortMappingResult.mapped(this.getExternalAddress(service).orElse(null));
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			LOGGER.debug("UPnP TCP port mapping interrupted for port {}", port);
			return PortMappingResult.notMapped();
		} catch (Exception e) {
			LOGGER.debug("UPnP TCP port mapping failed for port {}: {}", port, e.getMessage());
			return PortMappingResult.notMapped();
		}
	}

	@Override
	public void closeTcpPort(int port) {
		synchronized (this.lock) {
			if (this.upnpService == null && !this.mappedTcpPorts.contains(port))
				return;
		}

		try {
			RemoteService service = this.findWanConnectionService();
			if (service == null)
				return;

			this.deletePortMapping(service, port);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			LOGGER.debug("UPnP TCP port unmapping interrupted for port {}", port);
		} catch (Exception e) {
			LOGGER.debug("UPnP TCP port unmapping failed for port {}: {}", port, e.getMessage());
		} finally {
			boolean hasMappings;
			synchronized (this.lock) {
				this.mappedTcpPorts.remove(port);
				hasMappings = !this.mappedTcpPorts.isEmpty();
			}

			if (!hasMappings)
				this.close();
		}
	}

	@Override
	public void close() {
		synchronized (this.lock) {
			if (this.upnpService == null)
				return;

			try {
				this.upnpService.shutdown();
			} catch (Exception e) {
				LOGGER.debug("UPnP service shutdown failed: {}", e.getMessage());
			} finally {
				this.upnpService = null;
			}
		}
	}

	private RemoteService findWanConnectionService() throws InterruptedException {
		UpnpService service = this.getUpnpService();

		for (UDAServiceType serviceType : WAN_SERVICE_TYPES) {
			RemoteService wanService = this.findService(service, serviceType);
			if (wanService != null)
				return wanService;
		}

		for (UDAServiceType serviceType : WAN_SERVICE_TYPES)
			service.getControlPoint().search(new UDAServiceTypeHeader(serviceType));

		TimeUnit.SECONDS.sleep(DISCOVERY_WAIT_SECONDS);

		for (UDAServiceType serviceType : WAN_SERVICE_TYPES) {
			RemoteService wanService = this.findService(service, serviceType);
			if (wanService != null)
				return wanService;
		}

		return null;
	}

	private UpnpService getUpnpService() {
		synchronized (this.lock) {
			if (this.upnpService == null) {
				this.upnpService = new UpnpServiceImpl();
				this.upnpService.startup();
			}

			return this.upnpService;
		}
	}

	@SuppressWarnings("rawtypes")
	private RemoteService findService(UpnpService upnpService, UDAServiceType serviceType) {
		Collection devices = upnpService.getRegistry().getDevices(serviceType);
		for (Object deviceObject : devices) {
			if (!(deviceObject instanceof Device))
				continue;

			Service service = ((Device) deviceObject).findService(serviceType);
			if (service instanceof RemoteService)
				return (RemoteService) service;
		}

		return null;
	}

	private InetAddress getInternalAddress(RemoteService service) {
		if (!(service.getDevice() instanceof RemoteDevice))
			return null;

		return ((RemoteDevice) service.getDevice()).getIdentity().getDiscoveredOnLocalAddress();
	}

	private boolean addPortMapping(RemoteService service, int port, String internalAddress, String description)
			throws InterruptedException {
		ActionInvocation<?> actionInvocation = this.createActionInvocation(service, "AddPortMapping");
		if (actionInvocation == null)
			return false;

		actionInvocation.setInput("NewRemoteHost", "");
		actionInvocation.setInput("NewExternalPort", port);
		actionInvocation.setInput("NewProtocol", PROTOCOL_TCP);
		actionInvocation.setInput("NewInternalPort", port);
		actionInvocation.setInput("NewInternalClient", internalAddress);
		actionInvocation.setInput("NewEnabled", true);
		actionInvocation.setInput("NewPortMappingDescription", description);
		actionInvocation.setInput("NewLeaseDuration", 0);

		return this.executeAction(actionInvocation).isSuccess();
	}

	private boolean deletePortMapping(RemoteService service, int port) throws InterruptedException {
		ActionInvocation<?> actionInvocation = this.createActionInvocation(service, "DeletePortMapping");
		if (actionInvocation == null)
			return false;

		actionInvocation.setInput("NewRemoteHost", "");
		actionInvocation.setInput("NewExternalPort", port);
		actionInvocation.setInput("NewProtocol", PROTOCOL_TCP);

		return this.executeAction(actionInvocation).isSuccess();
	}

	private Optional<InetAddress> getExternalAddress(RemoteService service) throws InterruptedException {
		ActionInvocation<?> actionInvocation = this.createActionInvocation(service, "GetExternalIPAddress");
		if (actionInvocation == null)
			return Optional.empty();

		ActionResult result = this.executeAction(actionInvocation);
		if (!result.isSuccess())
			return Optional.empty();

		ActionArgumentValue<?> externalIpAddress = actionInvocation.getOutput("NewExternalIPAddress");
		if (externalIpAddress == null || externalIpAddress.getValue() == null)
			return Optional.empty();

		try {
			return Optional.of(InetAddress.getByName(externalIpAddress.getValue().toString()));
		} catch (Exception e) {
			LOGGER.debug("UPnP returned invalid external address: {}", externalIpAddress.getValue());
			return Optional.empty();
		}
	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
	private ActionInvocation<?> createActionInvocation(RemoteService service, String actionName) {
		Action action = service.getAction(actionName);
		if (action == null)
			return null;

		return new ActionInvocation(action);
	}

	private ActionResult executeAction(ActionInvocation<?> actionInvocation) throws InterruptedException {
		CountDownLatch latch = new CountDownLatch(1);
		ActionResult result = new ActionResult();

		this.getUpnpService().getControlPoint().execute(new ActionCallback(actionInvocation) {
			@Override
			public void success(ActionInvocation invocation) {
				result.markSuccess();
				latch.countDown();
			}

			@Override
			public void failure(ActionInvocation invocation, UpnpResponse operation, String defaultMsg) {
				result.markFailure(defaultMsg);
				latch.countDown();
			}
		});

		if (!latch.await(ACTION_WAIT_SECONDS, TimeUnit.SECONDS))
			result.markFailure("timed out");

		if (!result.isSuccess() && result.getFailureMessage() != null)
			LOGGER.debug("UPnP action {} failed: {}", actionInvocation.getAction().getName(), result.getFailureMessage());

		return result;
	}

	private static class ActionResult {
		private boolean success;
		private String failureMessage;

		void markSuccess() {
			this.success = true;
		}

		void markFailure(String failureMessage) {
			this.success = false;
			this.failureMessage = failureMessage;
		}

		boolean isSuccess() {
			return this.success;
		}

		String getFailureMessage() {
			return this.failureMessage;
		}
	}
}
