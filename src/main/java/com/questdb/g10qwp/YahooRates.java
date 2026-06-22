package com.questdb.g10qwp;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal Yahoo Finance reader for the US yield complex (^TNX 10Y, ^FVX 5Y, ^TYX
 * 30Y, ^IRX 13wk) and the Treasury futures.
 *
 * <p>Per spec §14.5 the robust spine of this demo is the real EOD curve plus the
 * deterministic {@link CurveEngine} walk, <b>not</b> live ticks — Yahoo intraday is
 * rate-limit-flaky and must never break a screen-share. So in this first version the
 * poller is a <i>garnish</i>: it can print the live US 10Y as a sanity anchor on
 * startup, but it does not perturb the deterministic walk (which keeps cross-table
 * consistency rock solid and the dataset reproducible run-to-run). Wiring a graceful
 * USD-level nudge into {@link CurveEngine#beginSecond} is a later, optional upgrade.
 */
public final class YahooRates {

    private static final Pattern PRICE =
            Pattern.compile("\"regularMarketPrice\"\\s*:\\s*([0-9]+(?:\\.[0-9]+)?)");

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    /** Fetch a quoted level (e.g. {@code ^TNX} → 10Y yield in %), or null on any failure. */
    public Double fetch(String ticker) {
        String url = "https://query1.finance.yahoo.com/v8/finance/chart/"
                + ticker + "?range=1d&interval=1m";
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(8))
                    .header("User-Agent", "Mozilla/5.0 (qwp-g10-rates)")
                    .GET()
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                return null;
            }
            Matcher m = PRICE.matcher(resp.body());
            return m.find() ? Double.parseDouble(m.group(1)) : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Print the live US 10Y (^TNX) as a startup sanity check; never throws. */
    public void logUsAnchor() {
        Double tnx = fetch("%5ETNX");
        if (tnx != null) {
            System.out.printf("[yahoo] live US 10Y (^TNX) ~ %.3f%% (sanity anchor; walk stays deterministic)%n", tnx);
        } else {
            System.out.println("[yahoo] live anchor unavailable; using deterministic seed curve (expected, fine).");
        }
    }
}
