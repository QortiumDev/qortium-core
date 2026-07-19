package org.qortium.api.gateway.resource;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks gateway routes that serve QDN content rather than the Core API.
 *
 * <p>The gateway's public API filter runs after Jersey routing so these routes
 * can remain public without granting the same access to API resources that
 * share the gateway's URL space.</p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface PublicQdnResource {
}
