/**********************************************************************************
 *
 * Copyright (c) 2015 The Sakai Foundation
 *
 * Original developers:
 *
 *   New York University
 *   Payten Giles
 *   Mark Triggs
 *
 * Licensed under the Educational Community License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.osedu.org/licenses/ECL-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 **********************************************************************************/

package org.sakaiproject.drive.tool.handlers;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.sakaiproject.component.cover.ServerConfigurationService;
import org.sakaiproject.tool.cover.SessionManager;

import java.util.stream.Collectors;
import java.util.Collections;
import java.io.FileInputStream;
import java.io.InputStreamReader;

import java.net.URL;

import org.sakaiproject.component.cover.ComponentManager;
import org.sakaiproject.content.api.ContentHostingService;
import org.sakaiproject.content.api.ContentCollection;
import org.sakaiproject.content.api.ContentResource;
import org.sakaiproject.entity.api.ResourceProperties;

public class SakaiResourceHandler implements Handler {

    ContentHostingService contentHostingService = (ContentHostingService) ComponentManager.get("org.sakaiproject.content.api.ContentHostingService");

    private String redirectTo = null;

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, Map<String, Object> context) {
        String[] bits = request.getPathInfo().split("/", 3);

        String siteId = (String) context.get("siteID");
        String requestedPath = "/group/" + siteId + "/";

        // path info starts with /sakai-drive/
        if (bits.length == 3) {
            requestedPath = "/" + bits[2];
        }

        if (!requestedPath.endsWith("/")) {
            requestedPath += "/";
        }

        try {
            // THINKME: Maybe context should be smrter!
            ContentCollection siteResources = contentHostingService.getCollection(requestedPath);

            context.put("resource", new ResourceTree(siteResources, contentHostingService));
            context.put("subpage", "resources");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public Map<String, List<String>> getFlashMessages() {
        return new HashMap<String, List<String>>();
    }

    public Errors getErrors() {
        return null;
    }

    public boolean hasRedirect() {
        return false;
    }

    public String getRedirect() {
        return "";
    }

    private interface Resource {
        public String getLabel();
        public boolean isFolder();
        public Collection<Resource> getChildren();
        public String getTypeClass();
        public String getPath();
        public List<Breadcrumb> getBreadcrumbs();
    }

    private class ResourceTree implements Resource {
        private ContentCollection root;
        private ContentHostingService contentHostingService;

        public ResourceTree(ContentCollection root, ContentHostingService contentHostingService) {
            this.root = root;
            this.contentHostingService = contentHostingService;
        }

        public boolean isFolder() {
            return true;
        }

        public String getTypeClass() {
            return "drive-folder";
        }

        public String getPath() {
            return root.getId();
        }

        public Collection<Resource> getChildren() {
            Collection<Resource> result = new ArrayList<>();

            try {
                List<String> children = root.getMembers();

                for (String resourceId : children) {
                    if (contentHostingService.isCollection(resourceId)) {
                        result.add(new ResourceTree(contentHostingService.getCollection(resourceId),
                                contentHostingService));
                    } else {
                        result.add(new ResourceItem(contentHostingService.getResource(resourceId)));
                    }
                }
            } catch (Exception e) {
                // FIXME: think about this!
            }

            return result;
        }

        public List<Breadcrumb> getBreadcrumbs() {
            List<Breadcrumb> result = new ArrayList<>();

            ContentCollection current = root;

            while (current != null && !"/group/".equals(current.getId())) {
                result.add(0, new Breadcrumb(current.getId(), getLabel(current)));
                current = current.getContainingCollection();
            }
            return result;
        }

        public String getLabel() {
            return getLabel(root);
        }

        private String getLabel(ContentCollection other) {
            return (String)other.getProperties().get(ResourceProperties.PROP_DISPLAY_NAME);
        }
    }

    private static class Breadcrumb {
        private String id;
        private String label;

        public Breadcrumb(String id, String label) {
            this.id = id;
            this.label = label;
        }

        public String getId() { return id; }
        public String getLabel() { return label; }
    }

    private class ResourceItem implements Resource {
        private ContentResource resource;

        public ResourceItem(ContentResource resource) {
            this.resource = resource;
        }

        public boolean isFolder() {
            return false;
        }

        public String getLabel() {
            return (String)resource.getProperties().get(ResourceProperties.PROP_DISPLAY_NAME);
        }

        public String getPath() {
            return resource.getId();
        }

        public Collection<Resource> getChildren() {
            return Collections.EMPTY_LIST;
        }

        public List<Breadcrumb> getBreadcrumbs() {
            return Collections.EMPTY_LIST;
        }


        public String getTypeClass() {
            return ((String) resource.getProperties().get(ResourceProperties.PROP_CONTENT_TYPE)).replace("/", "-");
        }
    }


}


