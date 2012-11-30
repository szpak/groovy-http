package org.kar.http

import groovy.servlet.GroovyServlet
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.servlet.ServletContextHandler
import org.lpny.groovyrestlet.GroovyRestlet
import org.vertx.groovy.core.Vertx
import org.vertx.groovy.core.http.HttpServer

import java.util.concurrent.Executors
import javax.servlet.http.HttpServletResponse

import spock.lang.*

/**
 * Created with IntelliJ IDEA.
 * User: krobinson
 */
class GroovyHttpServerTest extends Specification {

    static final int HTTP_SERVER_PORT = 8090
    static final String HTTP_SERVER_HOST = "http://localhost:$HTTP_SERVER_PORT/"
    static final int JETTY_SERVER_PORT = 8091
    static final String JETTY_SERVER_HOST = "http://localhost:$JETTY_SERVER_PORT"
    static final int RESTLET_SERVER_PORT = 8092
    static final String TEST_STRING = 'foobar'
    static final String MISSING_STRING_PARAM = "Missing 'string' param"

    @Shared
    com.sun.net.httpserver.HttpServer httpServer

    @Shared
    Server jettyServer

    @Shared
    org.restlet.Server restletServer

    @Shared
    org.restlet.Client restletClient

    def setupSpec() {
        // http://www.java2s.com/Tutorial/Java/0320__Network/LightweightHTTPServer.htm
        //configuring a Java 6 HttpServer
        InetSocketAddress addr = new InetSocketAddress(HTTP_SERVER_PORT);
        httpServer = com.sun.net.httpserver.HttpServer.create(addr, 0);
        httpServer.createContext("/", new MyEchoHandler());
        httpServer.setExecutor(Executors.newCachedThreadPool());
        httpServer.start();

        //configuring Jetty 8 with GroovyServlet support
        jettyServer = new Server(JETTY_SERVER_PORT)
        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.NO_SESSIONS);
        context.setContextPath("/");
        jettyServer.setHandler(context);
        context.resourceBase = 'src/main/webapp'
        context.addServlet(GroovyServlet, '*.groovy')
        jettyServer.start()

        //configuring a Restlet Server and Client using an external dsl file
        GroovyRestlet gr = new GroovyRestlet()
        gr.builder.setVariable('port', RESTLET_SERVER_PORT)
        (restletClient, restletServer) = gr.build(new File('src/test/resources/restlet/reverseRestlet.groovy').toURI())
    }

    def "HttpServer reverse test"() {
        when: 'We execute a GET request against HttpServer'
        def response = "$HTTP_SERVER_HOST?string=$TEST_STRING".toURL().text

        then: 'We get the same text back in reverse'
        response == TEST_STRING.reverse()
    }

    def "HttpServer missing params test"() {
        when: 'We forget to include the required parameter to HttpServer'
        String html
        final HttpURLConnection connection = HTTP_SERVER_HOST.toURL().openConnection()
        connection.inputStream.withReader { Reader reader ->
            html = reader.text
        }

        then: 'An exception is thrown and we get an HTTP 400 response'
        connection.responseCode == HttpServletResponse.SC_BAD_REQUEST
        def e = thrown(IOException)
    }

    def "JettyServer reverse test"() {
        when: 'We execute a GET request against a JettyServer hosted Groovlet'
        def response = "$JETTY_SERVER_HOST/reverse.groovy?string=$TEST_STRING".toURL().text

        then: 'We get the same text back in reverse'
        response == TEST_STRING.reverse()
    }

    def "JettyServer missing params test"() {
        when: 'We forget to include the required parameter to JettyServer'
        String html
        final HttpURLConnection connection = "$JETTY_SERVER_HOST/reverse.groovy".toURL().openConnection()
        connection.inputStream.withReader { Reader reader ->
            html = reader.text
        }

        then: 'An exception is thrown and we get an HTTP 400 response'
        connection.responseCode == HttpServletResponse.SC_BAD_REQUEST
        def e = thrown(IOException)
    }

    def "restlet"() {
        when: 'We use the Restlet Client to execute a GET request against the Restlet Server'
        String response = restletClient.get("http://localhost:$RESTLET_SERVER_PORT/?string=$TEST_STRING").entity.text

        then: 'We get the same text back in reverse'
        TEST_STRING.reverse() == response
    }

    def "restlet failure"() {
        when: 'We forget to include the required parameter to Restlet'
        org.restlet.data.Response response = restletClient.get("http://localhost:$RESTLET_SERVER_PORT")

        then: 'An exception is thrown and we get an HTTP 400 response indicated as a client error'
        response.status.isClientError()
        !response.status.isServerError()
        response.status.code == 400
        response.status.description == MISSING_STRING_PARAM
        null == response.entity.text
    }

    def "embedded vert.x"() {
        when: 'We run a vert.x server and create a matching vert.x client'
        Vertx vertx = Vertx.newVertx()
        final HttpServer server = vertx.createHttpServer()
        server.requestHandler { req ->
            if (req.params.get('string') == null) {
                req.response.with {
                    statusCode = 400
                    statusMessage = MISSING_STRING_PARAM
                    end()
                }
            }
            else {
                req.response.end(req.params['string'].reverse())
            }

        }.listen(8083, 'localhost')

        def client = vertx.createHttpClient(port: 8083, host: 'localhost')

        then: 'We get our standard error and success'
        client.getNow("/") { resp ->
            400 == resp.statusCode
            MISSING_STRING_PARAM == resp.statusMessage
        }

        client.getNow("/?string=$TEST_STRING") { resp ->
            200 == resp.statusCode
            resp.dataHandler { buffer ->
                TEST_STRING.reverse() == buffer.toString()
            }
        }

        cleanup:
        server.close()
    }

    def cleanupSpec() {
        httpServer.stop(0)
        jettyServer.stop()
        restletServer.stop()
    }
}