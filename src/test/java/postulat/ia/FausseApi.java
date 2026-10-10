package postulat.ia;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Un faux point d'accès {@code /v1/messages}, sur la boucle locale : il rend la réponse qu'on lui a
 * préparée et retient chaque requête. L'adaptateur s'y éprouve de bout en bout, SDK compris, sans
 * réseau ni clé ni coût.
 */
final class FausseApi implements AutoCloseable {

    record Requete(String corps, Headers entetes) {}

    private final HttpServer serveur;
    private final List<Requete> requetes = new CopyOnWriteArrayList<>();
    private volatile int statut = 200;
    private volatile String reponse = "{}";

    FausseApi() throws IOException {
        serveur = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        serveur.createContext("/v1/messages", echange -> {
            String corps = new String(echange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requetes.add(new Requete(corps, echange.getRequestHeaders()));
            byte[] octets = reponse.getBytes(StandardCharsets.UTF_8);
            echange.getResponseHeaders().add("Content-Type", "application/json");
            echange.sendResponseHeaders(statut, octets.length);
            try (OutputStream sortie = echange.getResponseBody()) {
                sortie.write(octets);
            }
        });
        serveur.start();
    }

    FausseApi repondre(int statut, String json) {
        this.statut = statut;
        this.reponse = json;
        return this;
    }

    Requete derniere() {
        return requetes.get(requetes.size() - 1);
    }

    String url() {
        return "http://" + serveur.getAddress().getHostString() + ":" + serveur.getAddress().getPort();
    }

    /** Sans nouvelle tentative, pour qu'un échec simulé se voie tout de suite. */
    AnthropicClient client() {
        return AnthropicOkHttpClient.builder().apiKey("cle-d-essai").baseUrl(url()).maxRetries(0).build();
    }

    /** Une réponse de l'API Messages, au format du fil. */
    static String message(String modele, String texte, String fin, long entree, long sortie, long lus, long ecrits) {
        return """
                {"id":"msg_essai","type":"message","role":"assistant","model":"%s",
                 "content":[{"type":"text","text":"%s"}],
                 "stop_reason":"%s","stop_sequence":null,
                 "usage":{"input_tokens":%d,"output_tokens":%d,"cache_read_input_tokens":%d,"cache_creation_input_tokens":%d}}
                """.formatted(modele, texte.replace("\\", "\\\\").replace("\"", "\\\""), fin, entree, sortie, lus, ecrits);
    }

    static String erreur(String type, String message) {
        return "{\"type\":\"error\",\"error\":{\"type\":\"" + type + "\",\"message\":\"" + message + "\"}}";
    }

    @Override
    public void close() {
        serveur.stop(0);
    }
}
