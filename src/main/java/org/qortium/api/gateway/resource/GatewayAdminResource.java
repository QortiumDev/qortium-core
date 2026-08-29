package org.qortium.api.gateway.resource;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.qortium.api.model.NodeInfo;
import org.qortium.api.model.NodeStatus;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.core.Response;

/**
 * Narrow admin-read surface for the QDN gateway.
 *
 * <p>The normal admin resource lives in the restricted API package, which the
 * gateway deliberately does not scan. Keep these two public compatibility
 * reads here rather than registering that package and unintentionally adding
 * settings, lifecycle, minting-key and other owner-only routes to the gateway.
 * The gateway public-API filter still requires each method/path to appear in
 * {@code publicApiPaths}.</p>
 */
@Path("/admin")
@Tag(name = "Gateway admin reads")
public class GatewayAdminResource {

	@GET
	@Path("/info")
	@Operation(
		summary = "Fetch generic node info through the gateway",
		responses = {
			@ApiResponse(
				content = @Content(mediaType = "application/json", schema = @Schema(implementation = NodeInfo.class))
			)
		}
	)
	public NodeInfo info() {
		return NodeInfo.current();
	}

	@GET
	@Path("/status")
	@Operation(
		summary = "Fetch node status through the gateway",
		responses = {
			@ApiResponse(
				content = @Content(mediaType = "application/json", schema = @Schema(implementation = NodeStatus.class))
			)
		}
	)
	public NodeStatus status() {
		return new NodeStatus();
	}

	/**
	 * Keep every other GET under /admin out of the public QDN catch-all. The
	 * public-API filter rejects these paths before this 404 fallback runs.
	 */
	@GET
	@Path("/{path:.*}")
	public Response unavailable() {
		return Response.status(Response.Status.NOT_FOUND).build();
	}
}
