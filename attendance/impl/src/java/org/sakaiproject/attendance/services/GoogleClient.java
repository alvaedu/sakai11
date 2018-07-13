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

package org.sakaiproject.attendance.services;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.auth.oauth2.CredentialRefreshListener;
import com.google.api.client.auth.oauth2.DataStoreCredentialRefreshListener;
import com.google.api.client.auth.oauth2.StoredCredential;
import com.google.api.client.auth.oauth2.TokenErrorResponse;
import com.google.api.client.auth.oauth2.TokenResponse;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.auth.oauth2.GoogleCredential;
import com.google.api.client.googleapis.auth.oauth2.GoogleTokenResponse;
import com.google.api.client.googleapis.batch.BatchRequest;
import com.google.api.client.googleapis.batch.json.JsonBatchCallback;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.googleapis.services.AbstractGoogleClient;
import com.google.api.client.googleapis.services.json.AbstractGoogleJsonClientRequest;
import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.client.util.store.DataStore;
import com.google.api.client.util.store.FileDataStoreFactory;
import com.google.api.services.sheets.v4.Sheets;
import com.google.api.services.sheets.v4.SheetsScopes;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.*;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.sakaiproject.component.cover.ServerConfigurationService;
import org.sakaiproject.tool.cover.SessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


// FIXME: split this class up.  Maybe want a .google package?
public class GoogleClient {
    private static final Logger LOG = LoggerFactory.getLogger(GoogleClient.class);

    private int requestsPerBatch = 100;

    // FIXME: What should this number be?
    private RateLimiter rateLimiter = new RateLimiter(100000, 100000);

    private HttpTransport httpTransport = null;
    private JacksonFactory jsonFactory = null;
//    private GoogleClientSecrets clientSecrets = null;

    private static final Set<String> SCOPES = SheetsScopes.all();

    public GoogleClient() {
        try {
            httpTransport = GoogleNetHttpTransport.newTrustedTransport();
            jsonFactory = JacksonFactory.getDefaultInstance();
//            clientSecrets = GoogleClientSecrets.load(jsonFactory,
//                new InputStreamReader(new FileInputStream(ServerConfigurationService.getSakaiHomePath() + "/client_secrets.json")));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public void rateLimitHit() {
        rateLimiter.rateLimitHit();
    }

    public Credential getCredential() throws Exception {
        String user = System.getenv("ATTENDANCE_GOOGLE_USER");
        String secret = System.getenv("ATTENDANCE_GOOGLE_SECRET");

        File dataStoreLocation = new File(ServerConfigurationService.getSakaiHomePath() + "/attendance_oauth");
        FileDataStoreFactory store = new FileDataStoreFactory(dataStoreLocation);

        // General idea: create the auth flow backed by a credential store;
        // check whether the store already has credentials for the user we
        // want.  If it doesn't, we go through the auth process.
        GoogleAuthorizationCodeFlow auth = new GoogleAuthorizationCodeFlow.Builder(httpTransport, jsonFactory,
            user, secret,
            SCOPES)
            .setAccessType("offline")
            .setDataStoreFactory(store)
            .build();

        Credential storedCredential = null;

        storedCredential = auth.loadCredential(user);

        if (storedCredential == null) {
            throw new RuntimeException("No stored credential was found for user: " + user);
        }

        // Take our credential and wrap it in a GoogleCredential.  As far as
        // I can tell, all this gives us is the ability to update our stored
        // credentials as they get refreshed (using the
        // DataStoreCredentialRefreshListener).
        Credential credential = new GoogleCredential.Builder()
            .setTransport(httpTransport)
            .setJsonFactory(jsonFactory)
            .setClientSecrets(user, secret)
            .addRefreshListener(new CredentialRefreshListener() {
                public void onTokenErrorResponse(Credential credential, TokenErrorResponse tokenErrorResponse) {
                    LOG.error("OAuth token refresh error: " + tokenErrorResponse);
                }

                public void onTokenResponse(Credential credential, TokenResponse tokenResponse) {
                    LOG.info("OAuth token was refreshed");
                }
            })
            .addRefreshListener(new DataStoreCredentialRefreshListener(user, store))
            .build();

        credential.setAccessToken(storedCredential.getAccessToken());
        credential.setRefreshToken(storedCredential.getRefreshToken());

        return credential;
    }

    public Sheets getSheets(String applicationName) throws Exception {
        return new Sheets.Builder(httpTransport, jsonFactory, getCredential())
            .setApplicationName(applicationName)
            .build();
    }


    public LimitedBatchRequest getBatch(AbstractGoogleClient client) throws Exception {
        return new LimitedBatchRequest(client);
    }

    public class LimitedBatchRequest {

        // According to the docs, Google sets their maximum to 1000, but we
        // can't really use that without hitting the rate limit.  See
        // RateLimiter above.
        private LinkedList<AbstractGoogleJsonClientRequest> requests = new LinkedList<>();
        private LinkedList<JsonBatchCallback> callbacks = new LinkedList<>();
        private AbstractGoogleClient client;


        public LimitedBatchRequest(AbstractGoogleClient client) throws Exception {
            requests = new LinkedList<AbstractGoogleJsonClientRequest>();
            callbacks = new LinkedList<JsonBatchCallback>();

            this.client = client;
        }

        public void queue(AbstractGoogleJsonClientRequest request, JsonBatchCallback callback) {
            requests.add(request);
            callbacks.add(callback);
        }

        public void execute() throws Exception {
            if (requests.isEmpty()) {
                return;
            }

            while (executeNextBatch()) {
                // To glory!
            }
        }

        private boolean executeNextBatch() throws Exception {
            if (requests.isEmpty()) {
                return false;
            }

            BatchRequest batch = client.batch(new HttpRequestInitializer() {
                @Override
                public void initialize(HttpRequest request) {
                    request.setConnectTimeout(15 * 60000);
                    request.setReadTimeout(15 * 60000);
                }
            });

            for (int i = 0; !requests.isEmpty() && i < GoogleClient.this.requestsPerBatch; i++) {
                AbstractGoogleJsonClientRequest request = requests.pop();
                JsonBatchCallback callback = callbacks.pop();

                request.queue(batch, callback);
            }

            if (batch.size() > 0) {
                GoogleClient.this.rateLimiter.wantQueries(batch.size());

                long start = System.currentTimeMillis();
                LOG.info("Executing batch of size: {}", batch.size());
                batch.execute();
                LOG.info("Batch finished in {} ms", System.currentTimeMillis() - start);
            }

            return !requests.isEmpty();
        }
    }

    private class RateLimiter {
        private long queriesPerTimestep;
        private long timestepMs;

        public RateLimiter(long queriesPerTimestep, long timestepMs) {
            this.queriesPerTimestep = queriesPerTimestep;
            this.timestepMs = timestepMs;
        }

        // Google limits to 1500 queries per 100 seconds by default.  This
        // appears to include the subqueries of batch requests (i.e. a single
        // batch request doesn't just count as one query.)
        private List<Long> times = new ArrayList<>();
        private Map<Long, Long> queryCounts = new HashMap<>();

        // Express an interest in running `count` queries.  Block until that's
        // OK.
        public synchronized void wantQueries(long count) {
            if (count > queriesPerTimestep) {
                throw new RuntimeException("Can't execute that many concurrent queries: " + count);
            }

            while ((queriesInLastTimestep() + count) >= queriesPerTimestep) {
                LOG.warn("Waiting for rate limiter to allow another {} queries", count);
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {}
            }

            // OK!
            recordQueries(count);
        }

        private void recordQueries(long count) {
            long now = System.currentTimeMillis();

            if (times.contains(now)) {
                queryCounts.put(now, queryCounts.get(now) + count);
            } else {
                times.add(now);
                queryCounts.put(now, count);
            }
        }


        private long queriesInLastTimestep() {
            long result = 0;
            long timestepStart = System.currentTimeMillis() - timestepMs;

            Iterator<Long> it = times.iterator();
            while (it.hasNext()) {
                long time = it.next();

                if (time < timestepStart) {
                    // Time expired.  No longer needed.
                    it.remove();
                    queryCounts.remove(time);
                } else {
                    result += queryCounts.get(time);
                }
            }

            return result;
        }

        public void rateLimitHit() {
            LOG.warn("Google rate limit hit!");
        }
    }
}