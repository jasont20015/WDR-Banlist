package com.wdrbanlist;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.annotations.VisibleForDevtools;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.util.Text;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

@Slf4j
@Singleton
public class BanlistManager {
    private static final HttpUrl WDRBansUrl = HttpUrl.parse("https://WDRDev.github.io/banlist.json");
    private static final Gson GSON = new GsonBuilder().setDateFormat("yyyy-MM-dd hh:mm:ss").create();
    private static final Type typeToken = new TypeToken<List<Ban>>() {
    }.getType();
   private final OkHttpClient client;
    private final Map<String, Ban> WDRBans = new ConcurrentHashMap<>();
    private final ClientThread clientThread;

    @Inject
    private BanlistManager(OkHttpClient client, ClientThread clientThread) {
        this.client = client;
        this.clientThread = clientThread;
    }
    /**
     * @param onComplete called once the list has been refreshed. Called on the client thread
     */
    public void refresh(Runnable onComplete) {
        Request rwReq = new Request.Builder().url(WDRBansUrl).build();

        // call on background thread
        client.newCall(rwReq).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                log.error("failed to get WDR Banlist: {}", e.toString());
            }

            @Override
            public void onResponse(Call call, Response response) {
                try {
                    List<Ban> bans = GSON.fromJson(new InputStreamReader(response.body().byteStream()), typeToken);
                    WDRBans.clear();
                    for (Ban b : bans) {
                        String rsn = Text.toJagexName(b.getRsn()).toLowerCase();
                        WDRBans.put(rsn, b);

                    }
                    log.info("saved {}/{} WDR Bans", WDRBans.size(), bans.size());
                    if (onComplete != null) {
                        clientThread.invokeLater(onComplete);
                    }
                } finally {
                    response.close();
                }

            }
        });
    }
    /**
     * Get a WDR ban from the cached list
     *
     * @param rsn
     * @return
     */
    public Ban get(String rsn) {
        return WDRBans.get(Text.removeTags(Text.toJagexName(rsn)).toLowerCase());
    }

 //   @VisibleForDevtools
    void put(String rsn) {
        WDRBans.put(rsn.toLowerCase(), new Ban(rsn, "6-mar-2020", ""));
    }
}
