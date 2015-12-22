/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.elasticsearch.repositories.hdfs;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;

import java.util.Collection;
import java.util.Collections;

import org.elasticsearch.action.admin.cluster.repositories.put.PutRepositoryResponse;
import org.elasticsearch.action.admin.cluster.snapshots.create.CreateSnapshotResponse;
import org.elasticsearch.action.admin.cluster.snapshots.restore.RestoreSnapshotResponse;
import org.elasticsearch.client.Client;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.repositories.RepositoryException;
import org.elasticsearch.repositories.hdfs.HdfsPlugin;
import org.elasticsearch.snapshots.SnapshotState;
import org.elasticsearch.test.ESIntegTestCase;
import org.elasticsearch.test.ESIntegTestCase.ClusterScope;
import org.elasticsearch.test.ESIntegTestCase.Scope;
import org.elasticsearch.test.ESSingleNodeTestCase;

public class HdfsTests extends ESSingleNodeTestCase {

    @Override
    protected Collection<Class<? extends Plugin>> getPlugins() {
        return pluginList(HdfsPlugin.class);
    }

    public void testSimpleWorkflow() {
        Client client = client();

        PutRepositoryResponse putRepositoryResponse = client.admin().cluster().preparePutRepository("test-repo")
                .setType("hdfs")
                .setSettings(Settings.settingsBuilder()
                        .put("uri", "hdfs:///")
                        .put("conf.fs.AbstractFileSystem.hdfs.impl", TestingFs.class.getName())
                        .put("path", "foo")
                        .put("chunk_size", randomIntBetween(100, 1000) + "k")
                        .put("compress", randomBoolean())
                        ).get();
        assertThat(putRepositoryResponse.isAcknowledged(), equalTo(true));

        createIndex("test-idx-1");
        createIndex("test-idx-2");
        createIndex("test-idx-3");
        ensureGreen();

        logger.info("--> indexing some data");
        for (int i = 0; i < 100; i++) {
            client().prepareIndex("test-idx-1", "doc", Integer.toString(i)).setSource("foo", "bar" + i).get();
            client().prepareIndex("test-idx-2", "doc", Integer.toString(i)).setSource("foo", "bar" + i).get();
            client().prepareIndex("test-idx-3", "doc", Integer.toString(i)).setSource("foo", "bar" + i).get();
        }
        client().admin().indices().prepareRefresh().get();
        assertThat(count(client, "test-idx-1"), equalTo(100L));
        assertThat(count(client, "test-idx-2"), equalTo(100L));
        assertThat(count(client, "test-idx-3"), equalTo(100L));

        logger.info("--> snapshot");
        CreateSnapshotResponse createSnapshotResponse = client.admin().cluster().prepareCreateSnapshot("test-repo", "test-snap").setWaitForCompletion(true).setIndices("test-idx-*", "-test-idx-3").get();
        assertThat(createSnapshotResponse.getSnapshotInfo().successfulShards(), greaterThan(0));
        assertThat(createSnapshotResponse.getSnapshotInfo().successfulShards(), equalTo(createSnapshotResponse.getSnapshotInfo().totalShards()));

        assertThat(client.admin().cluster().prepareGetSnapshots("test-repo").setSnapshots("test-snap").get().getSnapshots().get(0).state(), equalTo(SnapshotState.SUCCESS));

        logger.info("--> delete some data");
        for (int i = 0; i < 50; i++) {
            client.prepareDelete("test-idx-1", "doc", Integer.toString(i)).get();
        }
        for (int i = 50; i < 100; i++) {
            client.prepareDelete("test-idx-2", "doc", Integer.toString(i)).get();
        }
        for (int i = 0; i < 100; i += 2) {
            client.prepareDelete("test-idx-3", "doc", Integer.toString(i)).get();
        }
        client().admin().indices().prepareRefresh().get();
        assertThat(count(client, "test-idx-1"), equalTo(50L));
        assertThat(count(client, "test-idx-2"), equalTo(50L));
        assertThat(count(client, "test-idx-3"), equalTo(50L));

        logger.info("--> close indices");
        client.admin().indices().prepareClose("test-idx-1", "test-idx-2").get();

        logger.info("--> restore all indices from the snapshot");
        RestoreSnapshotResponse restoreSnapshotResponse = client.admin().cluster().prepareRestoreSnapshot("test-repo", "test-snap").setWaitForCompletion(true).execute().actionGet();
        assertThat(restoreSnapshotResponse.getRestoreInfo().totalShards(), greaterThan(0));

        ensureGreen();
        assertThat(count(client, "test-idx-1"), equalTo(100L));
        assertThat(count(client, "test-idx-2"), equalTo(100L));
        assertThat(count(client, "test-idx-3"), equalTo(50L));

        // Test restore after index deletion
        logger.info("--> delete indices");
        client().admin().indices().prepareDelete("test-idx-1", "test-idx-2").get();
        logger.info("--> restore one index after deletion");
        restoreSnapshotResponse = client.admin().cluster().prepareRestoreSnapshot("test-repo", "test-snap").setWaitForCompletion(true).setIndices("test-idx-*", "-test-idx-2").execute().actionGet();
        assertThat(restoreSnapshotResponse.getRestoreInfo().totalShards(), greaterThan(0));
        ensureGreen();
        assertThat(count(client, "test-idx-1"), equalTo(100L));
        ClusterState clusterState = client.admin().cluster().prepareState().get().getState();
        assertThat(clusterState.getMetaData().hasIndex("test-idx-1"), equalTo(true));
        assertThat(clusterState.getMetaData().hasIndex("test-idx-2"), equalTo(false));
    }

    public void testWrongPath() {
        Client client = client();

        try {
            PutRepositoryResponse putRepositoryResponse = client.admin().cluster().preparePutRepository("test-repo")
                    .setType("hdfs")
                    .setSettings(Settings.settingsBuilder()
                        .put("uri", "hdfs:///")
                        .put("conf.fs.AbstractFileSystem.hdfs.impl", TestingFs.class.getName())
                        .put("path", "a@b$c#11:22")
                        .put("chunk_size", randomIntBetween(100, 1000) + "k")
                        .put("compress", randomBoolean()))
                    .get();
            assertThat(putRepositoryResponse.isAcknowledged(), equalTo(true));

            createIndex("test-idx-1");
            createIndex("test-idx-2");
            createIndex("test-idx-3");
            ensureGreen();
            fail("Path name is invalid");
        } catch (RepositoryException re) {
            // expected
        }
    }

    public void testNonHdfsUri() {
        Client client = client();
        try {
            PutRepositoryResponse putRepositoryResponse = client.admin().cluster().preparePutRepository("test-repo")
                    .setType("hdfs")
                    .setSettings(Settings.settingsBuilder()
                        .put("uri", "file:///")
                        .put("conf.fs.AbstractFileSystem.hdfs.impl", TestingFs.class.getName())
                        .put("path", "should-fail")
                        .put("chunk_size", randomIntBetween(100, 1000) + "k")
                        .put("compress", randomBoolean()))
                    .get();
            assertThat(putRepositoryResponse.isAcknowledged(), equalTo(true));

            createIndex("test-idx-1");
            createIndex("test-idx-2");
            createIndex("test-idx-3");
            ensureGreen();
            fail("Path name is invalid");
        } catch (RepositoryException re) {
            // expected
        }
    }

    public void testPathSpecifiedInHdfs() {
        Client client = client();
        try {
            PutRepositoryResponse putRepositoryResponse = client.admin().cluster().preparePutRepository("test-repo")
                    .setType("hdfs")
                    .setSettings(Settings.settingsBuilder()
                        .put("uri", "hdfs:///some/path")
                        .put("conf.fs.AbstractFileSystem.hdfs.impl", TestingFs.class.getName())
                        .put("path", "should-fail")
                        .put("chunk_size", randomIntBetween(100, 1000) + "k")
                        .put("compress", randomBoolean()))
                    .get();
            assertThat(putRepositoryResponse.isAcknowledged(), equalTo(true));

            createIndex("test-idx-1");
            createIndex("test-idx-2");
            createIndex("test-idx-3");
            ensureGreen();
            fail("Path name is invalid");
        } catch (RepositoryException re) {
            // expected
        }
    }

    private long count(Client client, String index) {
        return client.prepareSearch(index).setSize(0).get().getHits().totalHits();
    }
}
