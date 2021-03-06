package ytextractor;

import android.os.AsyncTask;
import android.os.Handler;
import android.os.Looper;

//import com.commit451.youtubeextractor.JavaScriptUtil;
import com.google.gson.Gson;
import com.google.gson.JsonParser;

import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import ytextractor.model.PlayerResponse;
import ytextractor.model.Response;
import ytextractor.model.StreamingData;
import ytextractor.model.YTMedia;
import ytextractor.model.YTSubtitles;
import ytextractor.model.YoutubeMeta;
import ytextractor.utils.HTTPUtility;
import ytextractor.utils.LogUtils;
import ytextractor.utils.RegexUtils;
import ytextractor.utils.Utils;

public class YoutubeStreamExtractor extends AsyncTask<String, Void, Void> {


    Map<String, String> Headers = new HashMap<>();
    List<YTMedia> adaptiveMedia = new ArrayList<>();
    List<YTMedia> muxedMedia = new ArrayList<>();
    List<YTSubtitles> subtitle = new ArrayList<>();
    String regexUrl = ("(?<=url=).*");
    String regexYtshortLink = "(http|https)://(www\\.|)youtu.be/.*";
    String regexPageLink = ("(http|https)://(www\\.|m.|)youtube\\.com/watch\\?v=(.+?)( |\\z|&)");
    String regexFindReason = "(?<=(class=\"message\">)).*?(?=<)";
    String regexPlayerJson =
//            "ytplayer.config\\s*=\\s*(\\{.*?\\});";
            "(?<=ytplayer.config\\s=).*?((\\}(\n|)\\}(\n|))|(\\}))(?=;)";
    ExtractorListner listener;
    List<String> reasonUnavialable = Arrays.asList("This video is unavailable on this device.", "Content Warning", "who has blocked it on copyright grounds.");
    Handler han = new Handler(Looper.getMainLooper());
    private ExtractorException Ex;
    private Response response;
    private YoutubeMeta ytmeta;
    private Gson gson = new Gson();

    public YoutubeStreamExtractor(ExtractorListner EL) {
        this.listener = EL;
        Headers.put("Accept-Language", "en");
    }

    public YoutubeStreamExtractor useDefaultLogin() {
        Headers.put("Cookie", Utils.loginCookie);
        return setHeaders(Headers);
    }

    public Map<String, String> getHeaders() {
        return Headers;
    }

    public YoutubeStreamExtractor setHeaders(Map<String, String> headers) {
        Headers = headers;
        return this;
    }

    public void Extract(String VideoId) {
        this.execute(VideoId);
    }

    @Override
    protected void onPostExecute(Void result) {
        if (Ex != null) {
            listener.onExtractionGoesWrong(Ex);
        } else {
            listener.onExtractionDone(adaptiveMedia, muxedMedia, subtitle, ytmeta);
        }
    }

    @Override
    protected void onPreExecute() {
        Ex = null;
        adaptiveMedia.clear();
        muxedMedia.clear();

    }

    @Override
    protected void onCancelled() {
        if (Ex != null) {
            listener.onExtractionGoesWrong(Ex);
        }
    }


    @Override
    protected Void doInBackground(String[] ids) {

        String Videoid = Utils.extractVideoID(ids[0]);
        String jsonBody = null;
        try {
            String body = HTTPUtility.downloadPageSource("https://www.youtube.com/watch?v=" + Videoid + "&has_verified=1&bpctr=9999999999", Headers);

            jsonBody = parsePlayerConfig(body);
            PlayerResponse playerResponse = parseJson(jsonBody);

            ytmeta = playerResponse.videoDetails;
            subtitle = playerResponse.captions != null ? playerResponse.captions.getPlayerCaptionsTracklistRenderer().getCaptionTracks() : null;
            //Utils.copyToBoard(jsonBody);

            if (playerResponse.videoDetails.isLiveContent) {
                parseLiveUrls(playerResponse.streamingData);
            } else {
                StreamingData sd = playerResponse.streamingData;
                LogUtils.log("sizea= " + sd.getAdaptiveFormats().length);
                LogUtils.log("sizem= " + sd.getFormats().length);

                adaptiveMedia = parseUrls(sd.getAdaptiveFormats());
                muxedMedia = parseUrls(sd.getFormats());
                LogUtils.log("sizeXa= " + adaptiveMedia.size());
                LogUtils.log("sizeXm= " + muxedMedia.size());

            }
        } catch (Exception e) {
            LogUtils.log(Arrays.toString(e.getStackTrace()));
            Ex = new ExtractorException("Error While getting Youtube Data:" + e.getMessage());
            this.cancel(true);
        }
        return null;
    }

    /*this function creates Json models using Gson*/
    private PlayerResponse parseJson(String body) throws Exception {
        JsonParser parser = new JsonParser();
        response = gson.fromJson(parser.parse(body), Response.class);
        LogUtils.log("TEST: " + response.args.player_response);
        PlayerResponse playerResponse = gson.fromJson(response.args.player_response, PlayerResponse.class);
        return playerResponse;
    }

    /*This function is used to check if webpage contain steam data and then gets the Json part of from the page using regex*/
    private String parsePlayerConfig(String body) throws ExtractorException {
        if (Utils.isListContain(reasonUnavialable, RegexUtils.matchGroup(regexFindReason, body))) {
            throw new ExtractorException(RegexUtils.matchGroup(regexFindReason, body));
        }

        if (body.contains("ytplayer.config")) {
            return RegexUtils.matchGroup(regexPlayerJson, body);
        } else {
            throw new ExtractorException("This Video is unavialable");
        }
    }




    /*independent function Used to parse urls for adaptive & muxed stream with cipher protection*/

    private List<YTMedia> parseUrls(YTMedia[] rawMedia) {
        List<YTMedia> links = new ArrayList<>();
        try {
            String jsData = response.assets.getJsData();
            String enc = "UTF-8";
            LogUtils.log("JSDATA: " + jsData);
            for (int x = 0; x < rawMedia.length; x++) {
                YTMedia media = rawMedia[x];
                LogUtils.log("TEST: " +
                        (media.cipher != null ? media.cipher : "null cip")
                );
                boolean useCipher = media.useCipher();
                if (useCipher) {
                    String tempUrl = "";
                    String decodedSig = "";

                    String[] arr = media.cipher.split("&");
                    for (String s : arr) {
                        if (s.startsWith("s=")) {
                            String decoder = URLDecoder.decode(s.replace("s=", ""), enc);
                            decodedSig = CipherManager.dechiperSig(decoder, jsData);
                        }
                        if (s.startsWith("url=")) {
                            tempUrl = URLDecoder.decode(s.replace("url=", ""), enc);
                            for (String url_part : tempUrl.split("&")) {
                                if (url_part.startsWith("s=")) {
                                    decodedSig = CipherManager.dechiperSig(
                                            URLDecoder.decode(url_part.replace("s=", ""), enc),
                                            jsData);
                                }
                            }
                        }
                    }

                    String finalUrl = tempUrl + "&sig=" + decodedSig;
                    LogUtils.log("TEST: " + finalUrl);
                    media.url = finalUrl;
                }
                links.add(media);
            }

        } catch (Exception e) {
            LogUtils.log("ERROR: " + e.getMessage());
            Ex = new ExtractorException(e.getMessage());
            this.cancel(true);
        }
        return links;
    }





    /*This funtion parse live youtube videos links from streaming data  */

    private void parseLiveUrls(StreamingData streamData) throws Exception {
        if (streamData.getHlsManifestUrl() == null) {
            throw new ExtractorException("No link for hls video");
        }
        String hlsPageSource = HTTPUtility.downloadPageSource(streamData.getHlsManifestUrl());
        String regexhlsLinks = "(#EXT-X-STREAM-INF).*?(index.m3u8)";
        List<String> rawData = RegexUtils.getAllMatches(regexhlsLinks, hlsPageSource);
        for (String data : rawData) {
            YTMedia media = new YTMedia();
            String[] info_list = RegexUtils.matchGroup("(#).*?(?=https)", data).split(",");
            String live_url = RegexUtils.matchGroup("(https:).*?(index.m3u8)", data);
            media.url = live_url;
            for (String info : info_list) {
                if (info.startsWith("BANDWIDTH")) {
                    media.setBitrate(Integer.valueOf(info.replace("BANDWIDTH=", "")));
                }
                if (info.startsWith("CODECS")) {
                    media.mimeType = (info.replace("CODECS=", "").replace("\"", ""));
                }
                if (info.startsWith("FRAME-RATE")) {
                    media.setFps(Integer.valueOf((info.replace("FRAME-RATE=", ""))));
                }
                if (info.startsWith("RESOLUTION")) {
                    String[] RESOLUTION = info.replace("RESOLUTION=", "").split("x");
                    media.setWidth(Integer.valueOf(RESOLUTION[0]));
                    media.setHeight(Integer.valueOf(RESOLUTION[1]));
                    media.setQualityLabel(RESOLUTION[1] + "p");
                }
            }
            LogUtils.log(media.url);
            muxedMedia.add(media);
        }


    }

    public interface ExtractorListner {
        void onExtractionGoesWrong(ExtractorException e);

        void onExtractionDone(List<YTMedia> adativeStream, List<YTMedia> muxedStream, List<YTSubtitles> subList, YoutubeMeta meta);
    }

}     
