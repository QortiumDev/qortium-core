package org.qortium.api.restricted.resource;

import org.junit.Test;

import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class RenderResourceTests {

    @Test
    public void authorizationSuccessResponseUsesJsonScalarString() {
        Response response = RenderResource.authorizationSuccessResponse();

        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
        assertEquals("true", response.getEntity());
        assertEquals(MediaType.APPLICATION_JSON_TYPE, response.getMediaType());
    }

    @Test
    public void stripsDuplicateIdentifierPathSegment() {
        assertEquals("player.html",
                RenderResource.stripDuplicateIdentifierPathSegment("Emulator/player.html", "Emulator"));
    }

    @Test
    public void stripsDuplicateIdentifierWhenItIsTheOnlyPathSegment() {
        assertEquals("", RenderResource.stripDuplicateIdentifierPathSegment("Emulator", "Emulator"));
    }

    @Test
    public void keepsDifferentIdentifierPathSegment() {
        assertEquals("Other/player.html",
                RenderResource.stripDuplicateIdentifierPathSegment("Other/player.html", "Emulator"));
    }

    @Test
    public void keepsPathWhenIdentifierIsMissing() {
        assertEquals("Emulator/player.html",
                RenderResource.stripDuplicateIdentifierPathSegment("Emulator/player.html", null));
    }

    @Test
    public void keepsNullPath() {
        assertNull(RenderResource.stripDuplicateIdentifierPathSegment(null, "Emulator"));
    }
}
