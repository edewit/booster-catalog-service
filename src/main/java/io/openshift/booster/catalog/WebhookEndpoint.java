package io.openshift.booster.catalog;

import javax.enterprise.context.ApplicationScoped;
import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.core.MediaType;

@Path("/")
@ApplicationScoped
public class WebhookEndpoint {

    @POST
    @Path("/hook")
    @Consumes(MediaType.APPLICATION_JSON)
    public void hook(){}
}