package io.bitken.ss.web;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * Plan-98 Task 1 spike endpoint. Proves Quarkus boots and a browser can load a
 * page from this repo's toolchain. No config, no core data, no resolution yet.
 */
@Path("/")
public class HelloResource {

    @GET
    @Produces(MediaType.TEXT_HTML)
    public String index() {
        return """
            <!DOCTYPE html>
            <html lang="en">
              <head><meta charset="utf-8"><title>shipsmooth web spike</title></head>
              <body>
                <h1>shipsmooth web spike</h1>
                <p>Quarkus is serving a page from this repo's Gradle build on OpenJDK 25.</p>
                <p>Plan-98, Task 1 — the endpoint boots.</p>
              </body>
            </html>
            """;
    }
}
