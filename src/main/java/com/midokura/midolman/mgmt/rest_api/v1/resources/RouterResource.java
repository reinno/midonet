/*
 * @(#)RouterResource.java        1.6 11/09/05
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.rest_api.v1.resources;

import java.net.URI;
import java.util.UUID;

import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import com.midokura.midolman.mgmt.data.dao.RouterDataAccessor;
import com.midokura.midolman.mgmt.data.dto.Router;
import com.midokura.midolman.mgmt.rest_api.v1.resources.PortResource.RouterPortResource;
 
/**
 * Root resource class for Virtual Router.
 *
 * @version        1.6 05 Sept 2011
 * @author         Ryu Ishimoto
 */
@Path("/routers")
public class RouterResource extends RestResource {
    /*
     * Implements REST API endpoints for routers.
     */

    /**
     * Router resource locator for tenants
     */
    @Path("/{id}/ports")
    public RouterPortResource getPortResource(@PathParam("id") UUID id) {
        return new RouterPortResource(zookeeperConn, id);
    }    

    /**
     * Get the Router with the given ID.
     * @param id  Router UUID.
     * @return  Router object.
     */
    @GET
    @Path("{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Router get(@PathParam("id") UUID id) {
        // Get a router for the given ID.
        RouterDataAccessor dao = new RouterDataAccessor(zookeeperConn);
        Router router = null;
        try {
            router = dao.find(id);
        } catch (Exception ex) {
            // TODO: LOG
            System.err.println("Exception = " + ex.getMessage());
            throw new WebApplicationException(
                    Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .type(MediaType.APPLICATION_JSON).build());
        }
        return router;
    }

    /**
     * Sub-resource class for tenant's virtual router.
     */
    public static class TenantRouterResource extends RestResource {
        
        private UUID tenantId = null;
        
        /**
         * Default constructor.
         * 
         * @param   zkConn  Zookeeper connection string.
         * @param   tenantId  UUID of a tenant.
         */
        public TenantRouterResource(String zkConn, UUID tenantId) {
            this.zookeeperConn = zkConn;
            this.tenantId = tenantId;        
        }
        
        /**
         * Return a list of routers.
         * 
         * @return  A list of Router objects.
         */
        @GET
        @Produces(MediaType.APPLICATION_JSON)
        public Router[] list() {
            RouterDataAccessor dao = new RouterDataAccessor(zookeeperConn);
            Router[] routers = null;
            try {
                routers = dao.list(tenantId);
            } catch (Exception ex) {
                // TODO: LOG
                System.err.println("Exception = " + ex.getMessage());
                throw new WebApplicationException(
                        Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                        .type(MediaType.APPLICATION_JSON).build());           
            }
            return routers;
        }
        
        /**
         * Handler for create router API call.
         * 
         * @param   router  Router object mapped to the request input.
         * @returns Response object with 201 status code set if successful.
         */
        @POST
        @Consumes(MediaType.APPLICATION_JSON)
        @Produces(MediaType.APPLICATION_JSON)
        public Response create(Router router) {
            // Add a new router entry into zookeeper.
            router.setId(UUID.randomUUID());
            router.setTenantId(tenantId);
            RouterDataAccessor dao = new RouterDataAccessor(zookeeperConn);
            try {
                dao.create(router);
            } catch (Exception ex) {
                // TODO: LOG
                System.err.println("Exception = " + ex.getMessage());
                throw new WebApplicationException(
                        Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                        .type(MediaType.APPLICATION_JSON).build());
            }
            
            return Response.created(URI.create("/" + router.getId())).build();
        }        
    }    
}
