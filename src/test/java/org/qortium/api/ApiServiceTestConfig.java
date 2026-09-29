package org.qortium.api;
import org.glassfish.jersey.server.ResourceConfig;
/** Test-only access to the exact production provider configuration. */
public final class ApiServiceTestConfig {
 public static ResourceConfig create() { return ApiService.createResourceConfig(); }
}
