package io.openshift.booster.catalog;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Writer;
import java.net.URL;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;
import javax.ws.rs.core.StreamingOutput;
import javax.xml.bind.annotation.XmlRootElement;

@Path("/repo")
public class MavenRepository {

    @GET
    @Path("/{groupId: .+?}/{artifactId}/{version}/{file}")
    @Produces({ MediaType.APPLICATION_XML })
    public Response getFile(@PathParam("groupId") String groupId, @PathParam("artifactId") String artifactId,
            @PathParam("version") String version, @PathParam("file") String file) throws Exception {
        groupId = groupId.replace('/', '.');
        if (file.endsWith("pom")) {
            Pom pom = new Pom(groupId, artifactId, version);
            return Response.ok(pom).build();
        } else if (file.endsWith("jar")) {
            // https://github.com/openshiftio-vertx-boosters/vertx-http-booster/archive/v7.zip

            InputStream sourceZip = new URL(
                    "https://github.com/" + groupId + "/" + artifactId + "/archive/v" + version + ".zip").openStream();
            StreamingOutput stream = new StreamingOutput() {
                @Override
                public void write(OutputStream os) throws IOException, WebApplicationException {
                    byte[] buffer = new byte[1024];
                    int len;
                    while ((len = sourceZip.read(buffer)) != -1) {
                        os.write(buffer, 0, len);
                    }
                }
            };
            return Response.ok(stream).type("application/zip")
                    .header("Content-Disposition", "attachment; filename=\"" + file + ".zip\"").build();
        }

        return Response.status(Status.NOT_FOUND).build();
    }

    @XmlRootElement(name = "project")
    public static class Pom {
        private String groupId;
        private String artifactId;
        private String version;
        public String modelVersion = "4.0.0";

        public Pom() {
        }

        public Pom(String groupId, String artifactId, String version) {
            this.groupId = groupId;
            this.artifactId = artifactId;
            this.version = version;
        }

        public void setGroupId(String groupId) {
            this.groupId = groupId;
        }

        public String getGroupId() {
            return groupId;
        }

        public void setArtifactId(String artifactId) {
            this.artifactId = artifactId;
        }

        public String getArtifactId() {
            return artifactId;
        }

        public void setVersion(String version) {
            this.version = version;
        }

        public String getVersion() {
            return version;
        }
    }

}
