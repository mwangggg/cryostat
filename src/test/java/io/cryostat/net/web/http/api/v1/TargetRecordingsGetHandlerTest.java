/*
 * Copyright The Cryostat Authors
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or data
 * (collectively the "Software"), free of charge and under any and all copyright
 * rights in the Software, and any and all patent rights owned or freely
 * licensable by each licensor hereunder covering either (i) the unmodified
 * Software as contributed to or provided by such licensor, or (ii) the Larger
 * Works (as defined below), to deal in both
 *
 * (a) the Software, and
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software (each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 * The above copyright notice and either this complete permission notice or at
 * a minimum a reference to the UPL must be included in all copies or
 * substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package io.cryostat.net.web.http.api.v1;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import org.openjdk.jmc.common.unit.IQuantity;
import org.openjdk.jmc.common.unit.QuantityConversionException;
import org.openjdk.jmc.rjmx.services.jfr.IFlightRecorderService;
import org.openjdk.jmc.rjmx.services.jfr.IRecordingDescriptor;

import io.cryostat.MainModule;
import io.cryostat.core.log.Logger;
import io.cryostat.core.net.JFRConnection;
import io.cryostat.jmc.serialization.HyperlinkedSerializableRecordingDescriptor;
import io.cryostat.net.AuthManager;
import io.cryostat.net.ConnectionDescriptor;
import io.cryostat.net.TargetConnectionManager;
import io.cryostat.net.security.ResourceAction;
import io.cryostat.net.web.WebServer;
import io.cryostat.recordings.RecordingMetadataManager;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import io.vertx.core.MultiMap;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.RoutingContext;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;

@ExtendWith(MockitoExtension.class)
class TargetRecordingsGetHandlerTest {

    TargetRecordingsGetHandler handler;
    @Mock AuthManager auth;
    @Mock TargetConnectionManager connectionManager;
    @Mock WebServer webServer;
    @Mock RecordingMetadataManager recordingMetadataManager;
    @Mock Logger logger;
    Gson gson = MainModule.provideGson(logger);

    @BeforeEach
    void setup() {
        this.handler =
                new TargetRecordingsGetHandler(
                        auth, connectionManager, () -> webServer, recordingMetadataManager, gson);
    }

    @Test
    void shouldHandleGETRequest() {
        MatcherAssert.assertThat(handler.httpMethod(), Matchers.equalTo(HttpMethod.GET));
    }

    @Test
    void shouldHandleCorrectPath() {
        MatcherAssert.assertThat(
                handler.path(), Matchers.equalTo("/api/v1/targets/:targetId/recordings"));
    }

    @Test
    void shouldHaveExpectedRequiredPermissions() {
        MatcherAssert.assertThat(
                handler.resourceActions(),
                Matchers.equalTo(
                        Set.of(ResourceAction.READ_TARGET, ResourceAction.READ_RECORDING)));
    }

    @Test
    void shouldRespondWithErrorIfExceptionThrown() throws Exception {
        Mockito.when(
                        connectionManager.executeConnectedTask(
                                Mockito.any(ConnectionDescriptor.class), Mockito.any()))
                .thenThrow(new Exception("dummy exception"));

        RoutingContext ctx = Mockito.mock(RoutingContext.class);
        Mockito.when(ctx.pathParam("targetId")).thenReturn("foo:9091");
        HttpServerRequest req = Mockito.mock(HttpServerRequest.class);
        Mockito.when(ctx.request()).thenReturn(req);
        Mockito.when(req.headers()).thenReturn(MultiMap.caseInsensitiveMultiMap());

        Assertions.assertThrows(Exception.class, () -> handler.handleAuthenticated(ctx));
    }

    @Test
    void shouldRespondWithRecordingsList() throws Exception {
        JFRConnection connection = Mockito.mock(JFRConnection.class);
        IFlightRecorderService service = Mockito.mock(IFlightRecorderService.class);

        Mockito.when(
                        connectionManager.executeConnectedTask(
                                Mockito.any(ConnectionDescriptor.class), Mockito.any()))
                .thenAnswer(
                        arg0 ->
                                ((TargetConnectionManager.ConnectedTask<Object>)
                                                arg0.getArgument(1))
                                        .execute(connection));
        Mockito.when(connection.getService()).thenReturn(service);
        Mockito.when(connection.getHost()).thenReturn("fooHost");
        Mockito.when(connection.getPort()).thenReturn(1);
        List<IRecordingDescriptor> descriptors =
                Arrays.asList(createDescriptor("foo"), createDescriptor("bar"));
        Mockito.when(service.getAvailableRecordings()).thenReturn(descriptors);
        Mockito.when(
                        webServer.getDownloadURL(
                                Mockito.any(JFRConnection.class), Mockito.anyString()))
                .thenAnswer(
                        new Answer<String>() {
                            @Override
                            public String answer(InvocationOnMock invocation) throws Throwable {
                                return String.format(
                                        "http://example.com:1234/api/v1/targets/%s:%d/recordings/%s",
                                        ((JFRConnection) invocation.getArguments()[0]).getHost(),
                                        ((JFRConnection) invocation.getArguments()[0]).getPort(),
                                        invocation.getArguments()[1]);
                            }
                        });
        Mockito.when(webServer.getReportURL(Mockito.any(JFRConnection.class), Mockito.anyString()))
                .thenAnswer(
                        new Answer<String>() {
                            @Override
                            public String answer(InvocationOnMock invocation) throws Throwable {
                                return String.format(
                                        "http://example.com:1234/api/v1/targets/%s:%d/reports/%s",
                                        ((JFRConnection) invocation.getArguments()[0]).getHost(),
                                        ((JFRConnection) invocation.getArguments()[0]).getPort(),
                                        invocation.getArguments()[1]);
                            }
                        });

        RoutingContext ctx = Mockito.mock(RoutingContext.class);
        HttpServerResponse resp = Mockito.mock(HttpServerResponse.class);
        Mockito.when(ctx.response()).thenReturn(resp);
        Mockito.when(ctx.pathParam("targetId")).thenReturn("foo:9091");
        HttpServerRequest req = Mockito.mock(HttpServerRequest.class);
        Mockito.when(ctx.request()).thenReturn(req);
        Mockito.when(req.headers()).thenReturn(MultiMap.caseInsensitiveMultiMap());

        handler.handleAuthenticated(ctx);

        ArgumentCaptor<String> responseCaptor = ArgumentCaptor.forClass(String.class);
        Mockito.verify(resp).end(responseCaptor.capture());
        List<HyperlinkedSerializableRecordingDescriptor> result =
                gson.fromJson(
                        responseCaptor.getValue(),
                        new TypeToken<
                                List<HyperlinkedSerializableRecordingDescriptor>>() {}.getType());

        MatcherAssert.assertThat(
                result,
                Matchers.equalTo(
                        Arrays.asList(
                                new HyperlinkedSerializableRecordingDescriptor(
                                        createDescriptor("foo"),
                                        "http://example.com:1234/api/v1/targets/fooHost:1/recordings/foo",
                                        "http://example.com:1234/api/v1/targets/fooHost:1/reports/foo"),
                                new HyperlinkedSerializableRecordingDescriptor(
                                        createDescriptor("bar"),
                                        "http://example.com:1234/api/v1/targets/fooHost:1/recordings/bar",
                                        "http://example.com:1234/api/v1/targets/fooHost:1/reports/bar"))));
    }

    private static IRecordingDescriptor createDescriptor(String name)
            throws QuantityConversionException {
        IQuantity zeroQuantity = Mockito.mock(IQuantity.class);
        IRecordingDescriptor descriptor = Mockito.mock(IRecordingDescriptor.class);
        Mockito.when(descriptor.getId()).thenReturn(1L);
        Mockito.when(descriptor.getName()).thenReturn(name);
        Mockito.when(descriptor.getState()).thenReturn(IRecordingDescriptor.RecordingState.STOPPED);
        Mockito.when(descriptor.getStartTime()).thenReturn(zeroQuantity);
        Mockito.when(descriptor.getDuration()).thenReturn(zeroQuantity);
        Mockito.when(descriptor.isContinuous()).thenReturn(false);
        Mockito.when(descriptor.getToDisk()).thenReturn(false);
        Mockito.when(descriptor.getMaxSize()).thenReturn(zeroQuantity);
        Mockito.when(descriptor.getMaxAge()).thenReturn(zeroQuantity);
        return descriptor;
    }
}
