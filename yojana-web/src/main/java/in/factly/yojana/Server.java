/*
 * Copyright (c) 2015 Factly. All rights reserved.
 */

package in.factly.yojana;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.templ.MVELTemplateEngine;
import io.vertx.ext.web.templ.TemplateEngine;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public class Server extends AbstractVerticle {
    private static final Logger logger = LoggerFactory.getLogger(Server.class);
    private static final DateTimeFormatter fromDateFormatter = DateTimeFormatter.ofPattern("MMM, yyyy");

    @Override
    public void start(Future<Void> startFuture) {
        TemplateEngine engine = MVELTemplateEngine.create();

        HttpServer server = vertx.createHttpServer();
        Router router = Router.router(vertx);

        // Entry point to the application.
        router.get().handler(ctx -> query(ctx, engine));

        // TODO: Use vert.x config to set listener port and solr service address.
        server.requestHandler(router::accept).listen(8090);
        logger.info("Server running on port: 8090");

        startFuture.complete();
    }

    private void query(RoutingContext ctx, TemplateEngine engine) {
        HttpClientOptions options = new HttpClientOptions().setDefaultPort(8983);
        HttpClient client = vertx.createHttpClient(options);
        HttpClientRequest request;

        // TODO: Refactor if-else logical blocks into methods.
        if (ctx.request().getParam("id") != null) {
            String solrQuery = "/solr/yojana/select?df=id&wt=json&start=0&rows=1&q=" + ctx.request().getParam("id");

            request = requestHandler(client, ctx, engine, solrQuery,
                    "templates/details.templ", (r, c) -> {
                        JsonArray docs = r.getJsonObject("response").getJsonArray("docs");
                        JsonObject doc = docs != null && docs.size() > 0 ? docs.getJsonObject(0) : new JsonObject();
                        c.put("doc", doc);
                    });
        }
        else if (ctx.request().getParam("search") != null) {
            String phrase = ctx.request().getParam("search").replace(" ", "+");
            // TODO: Add facet queries.
            // Ex: facet=true&facet.range=income_per_annum&facet.range.start=20000&facet.range.end=60000&facet.range.gap=10000
            String solrQuery = "/solr/yojana/select?defType=edismax&qf=name+text&wt=json&start=0&rows=10&q=" + phrase;

            request = requestHandler(client, ctx, engine, solrQuery,
                    "templates/browse.templ",
                    (r, c) -> c.put("docs", r.getJsonObject("response").getJsonArray("docs")));
        }
        else {
            request = requestHandler(client, ctx, engine, "/solr/yojana/select?q=*:*&wt=json&start=0&rows=3",
                    "templates/index.templ", (r, c) -> {
                        c.put("docs", r.getJsonObject("response").getJsonArray("docs"));
                        c.put("fromDT", fromDateFormatter);
                    });
        }

        request.exceptionHandler(h -> {
            logger.error("Failed to query the index.", h.getCause());
            client.close();
            ctx.fail(500);
        });

        request.end();
    }

    private HttpClientRequest requestHandler(HttpClient client, RoutingContext ctx, TemplateEngine engine,
                                String solrQuery, String templatePath, SolrReader reader) {
        return client.request(HttpMethod.GET, "localhost", solrQuery, response -> {
            response.bodyHandler(b -> {
                try {
                    JsonObject result = new JsonObject(b.toString());
                    client.close();
                    reader.read(result, ctx);
                    render(ctx, engine, templatePath);
                }
                catch (Exception ex) {
                    logger.error("Unable to parse Solr response", ex);
                    ctx.fail(500);
                }
            });
        });
    }

    private void render(RoutingContext ctx, TemplateEngine engine, String template) {
        engine.render(ctx, template, res -> {
            if (res.succeeded()) {
                ctx.response().end(res.result());
            }
            else {
                logger.error("Unable to render the template.", res.cause());
                ctx.fail(500);
            }
        });
    }

    @FunctionalInterface
    private interface SolrReader {
        void read(JsonObject result, RoutingContext ctx);
    }
}
