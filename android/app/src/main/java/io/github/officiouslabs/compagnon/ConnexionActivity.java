package io.github.officiouslabs.compagnon;

import android.app.Activity;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.webkit.WebSettingsCompat;
import androidx.webkit.WebViewFeature;

import java.util.Collections;

/**
 * Page de connexion OneDrive ordinaire, présentée comme un Chrome standard (sans mention d'appli).
 * Dès que la session SharePoint existe (cookie FedAuth sur l'hôte visé), on revient à l'appli.
 */
public class ConnexionActivity extends Activity {
    static final String URL = "url";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        final String cible = getIntent().getStringExtra(URL);
        final String hote = Uri.parse(cible).getHost();
        WebView w = new WebView(this);
        setContentView(w);
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setUserAgentString(OneDriveWeb.agent(this));
        /* pas d'en-tête X-Requested-With révélant l'appli */
        if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST))
            WebSettingsCompat.setRequestedWithHeaderOriginAllowList(s, Collections.emptySet());
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true);
        w.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) { return false; }
            @Override public void onPageFinished(WebView v, String url) {
                String c = CookieManager.getInstance().getCookie("https://" + hote + "/");
                Uri u = Uri.parse(url);
                if (c != null && c.contains("FedAuth=") && hote.equalsIgnoreCase(u.getHost())) {
                    CookieManager.getInstance().flush();
                    setResult(RESULT_OK);
                    finish();
                }
            }
        });
        w.loadUrl(cible);
    }

    @Override
    public void onBackPressed() {
        setResult(RESULT_CANCELED);
        finish();
    }
}
