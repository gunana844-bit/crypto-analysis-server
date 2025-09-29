import fi.iki.elonen.NanoHTTPD;
import okhttp3.*;
import com.google.gson.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

public class CryptoAnalysisServer extends NanoHTTPD {
    
    private static final int PORT = 8080;
    private final OkHttpClient httpClient;
    private final Gson gson = new Gson();
    
    // WebSocket management
    private WebSocket ws;
    private volatile boolean isRunning = true;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    
    // Data buffers per timeframe
    private final Map<String, Deque<Candle>> bufferH4 = new ConcurrentHashMap<>();
    private final Map<String, Deque<Candle>> bufferH1 = new ConcurrentHashMap<>();
    private final Map<String, Deque<Candle>> bufferM15 = new ConcurrentHashMap<>();
    
    // Buffer limits
    private static final int MAX_H4 = 150;
    private static final int MAX_H1 = 500;
    private static final int MAX_M15 = 1000;
    
    // Top pairs (akan diupdate dinamis nanti)
    private List<String> topPairs = Arrays.asList(
        "btcusdt", "ethusdt", "bnbusdt", "solusdt", "adausdt",
        "xrpusdt", "dogeusdt", "avaxusdt", "shibusdt", "dotusdt",
        "maticusdt", "ltcusdt", "uniusdt", "atomusdt", "linkusdt",
        "etcusdt", "xlmusdt", "bchusdt", "vetusdt", "filusdt",
        "trxusdt", "nearusdt", "algousdt", "ftmusdt", "manausdt"
    );
    
    // Analysis results cache
    private final Map<String, AnalysisResult> analysisCache = new ConcurrentHashMap<>();
    private volatile long lastAnalysisTime = 0;
    
    // Constructor
    public CryptoAnalysisServer() throws IOException {
        super(PORT);
        
        // Setup OkHttp with timeouts
        httpClient = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build();
        
        // Start server
        start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
        System.out.println("[INFO] Server started on port " + PORT);
        System.out.println("[INFO] Access dashboard at http://127.0.0.1:" + PORT);
        
        // Initialize data collection
        initializeDataCollection();
    }
    
    private void initializeDataCollection() {
        System.out.println("[INFO] Initializing data collection...");
        
        // Fetch initial OHLCV data
        fetchInitialData();
        
        // Start WebSocket after initial data loaded
        scheduler.schedule(this::startWebSocketCollector, 10, TimeUnit.SECONDS);
        
        // Schedule periodic REST updates
        scheduler.scheduleWithFixedDelay(this::updateTopPairs, 0, 12, TimeUnit.HOURS);
        scheduler.scheduleWithFixedDelay(this::fetchPeriodicSnapshots, 5, 1, TimeUnit.MINUTES);
    }
    
    private void fetchInitialData() {
        System.out.println("[INFO] Fetching initial OHLCV data...");
        
        // Fetch different timeframes
        fetchOHLCVBatch(topPairs, "4h", bufferH4, MAX_H4);
        fetchOHLCVBatch(topPairs, "1h", bufferH1, MAX_H1);
        fetchOHLCVBatch(topPairs, "15m", bufferM15, MAX_M15);
    }
    
    private void fetchOHLCVBatch(List<String> pairs, String interval, 
                                  Map<String, Deque<Candle>> buffer, int maxSize) {
        final int batchSize = 5;
        final int delayMs = 6000;
        
        for (int i = 0; i < pairs.size(); i += batchSize) {
            final int start = i;
            final int end = Math.min(i + batchSize, pairs.size());
            final List<String> batch = pairs.subList(start, end);
            
            scheduler.schedule(() -> {
                for (String pair : batch) {
                    fetchSinglePairOHLCV(pair, interval, buffer, maxSize);
                }
            }, (i / batchSize) * delayMs, TimeUnit.MILLISECONDS);
        }
    }
    
    private void fetchSinglePairOHLCV(String pair, String interval,
                                      Map<String, Deque<Candle>> buffer, int maxSize) {
        String url = String.format(
            "https://fapi.binance.com/fapi/v1/klines?symbol=%s&interval=%s&limit=500",
            pair.toUpperCase(), interval
        );
        
        Request request = new Request.Builder().url(url).build();
        
        try (Response response = httpClient.newCall(request).execute()) {
            if (response.isSuccessful() && response.body() != null) {
                String body = response.body().string();
                JsonArray klines = JsonParser.parseString(body).getAsJsonArray();
                
                Deque<Candle> candleDeque = buffer.computeIfAbsent(pair, k -> new LinkedList<>());
                candleDeque.clear();
                
                for (JsonElement elem : klines) {
                    JsonArray c = elem.getAsJsonArray();
                    Candle candle = new Candle(
                        c.get(0).getAsLong(),   // timestamp
                        c.get(1).getAsDouble(),  // open
                        c.get(2).getAsDouble(),  // high
                        c.get(3).getAsDouble(),  // low
                        c.get(4).getAsDouble(),  // close
                        c.get(5).getAsDouble()   // volume
                    );
                    candleDeque.addLast(candle);
                    if (candleDeque.size() > maxSize) {
                        candleDeque.removeFirst();
                    }
                }
                System.out.println("[OK] Loaded " + interval + " data for " + pair.toUpperCase());
            }
        } catch (Exception e) {
            System.err.println("[ERROR] Failed to fetch " + interval + " for " + pair + ": " + e.getMessage());
        }
    }
    
    private void startWebSocketCollector() {
        String streams = String.join("/", 
            topPairs.stream().map(p -> p + "@kline_1m").toList()
        );
        String wsUrl = "wss://fstream.binance.com/stream?streams=" + streams;
        
        Request request = new Request.Builder().url(wsUrl).build();
        
        ws = httpClient.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                System.out.println("[INFO] WebSocket connected");
            }
            
            @Override
            public void onMessage(WebSocket webSocket, String text) {
                try {
                    processWebSocketMessage(text);
                } catch (Exception e) {
                    System.err.println("[ERROR] Processing WS message: " + e.getMessage());
                }
            }
            
            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                System.err.println("[ERROR] WebSocket failure: " + t.getMessage());
                reconnectWebSocket();
            }
            
            @Override
            public void onClosed(WebSocket webSocket, int code, String reason) {
                System.out.println("[INFO] WebSocket closed: " + reason);
                if (isRunning) {
                    reconnectWebSocket();
                }
            }
        });
    }
    
    private void processWebSocketMessage(String text) {
        JsonObject json = gson.fromJson(text, JsonObject.class);
        if (json.has("data")) {
            JsonObject data = json.getAsJsonObject("data");
            String symbol = data.get("s").getAsString().toLowerCase();
            JsonObject kline = data.getAsJsonObject("k");
            
            // Only process closed candles
            boolean isFinal = kline.get("x").getAsBoolean();
            if (isFinal) {
                Candle candle = new Candle(
                    kline.get("t").getAsLong(),
                    kline.get("o").getAsDouble(),
                    kline.get("h").getAsDouble(),
                    kline.get("l").getAsDouble(),
                    kline.get("c").getAsDouble(),
                    kline.get("v").getAsDouble()
                );
                updateTimeframes(symbol, candle);
            }
        }
    }
    
    private void updateTimeframes(String symbol, Candle minute1) {
        // Update M15
        aggregateCandle(symbol, minute1, bufferM15, 15 * 60 * 1000L, MAX_M15);
        // Update H1
        aggregateCandle(symbol, minute1, bufferH1, 60 * 60 * 1000L, MAX_H1);
        // Update H4
        aggregateCandle(symbol, minute1, bufferH4, 4 * 60 * 60 * 1000L, MAX_H4);
    }
    
    private void aggregateCandle(String symbol, Candle minute1, 
                                 Map<String, Deque<Candle>> buffer,
                                 long intervalMs, int maxSize) {
        Deque<Candle> deque = buffer.computeIfAbsent(symbol, k -> new LinkedList<>());
        
        if (deque.isEmpty()) {
            long bucketTime = (minute1.timestamp / intervalMs) * intervalMs;
            deque.addLast(new Candle(bucketTime, minute1.open, minute1.high,
                                     minute1.low, minute1.close, minute1.volume));
            return;
        }
        
        Candle last = deque.peekLast();
        long currentBucket = (minute1.timestamp / intervalMs) * intervalMs;
        
        if (currentBucket == last.timestamp) {
            // Update existing candle
            last.high = Math.max(last.high, minute1.high);
            last.low = Math.min(last.low, minute1.low);
            last.close = minute1.close;
            last.volume += minute1.volume;
        } else if (currentBucket > last.timestamp) {
            // New candle
            deque.addLast(new Candle(currentBucket, minute1.open, minute1.high,
                                     minute1.low, minute1.close, minute1.volume));
            if (deque.size() > maxSize) {
                deque.removeFirst();
            }
        }
    }
    
    private void reconnectWebSocket() {
        scheduler.schedule(() -> {
            System.out.println("[INFO] Reconnecting WebSocket...");
            startWebSocketCollector();
        }, 10, TimeUnit.SECONDS);
    }
    
    private void updateTopPairs() {
        // Update top 50 pairs by volume
        System.out.println("[INFO] Updating top pairs list...");
        // Implementation here (REST call to get 24hr ticker)
    }
    
    private void fetchPeriodicSnapshots() {
        // Periodic snapshot updates
        // Lighter than initial fetch
    }
    
    // MTF Analysis
    private AnalysisResult analyzePairMTF(String pair) {
        Deque<Candle> h4 = bufferH4.get(pair);
        Deque<Candle> h1 = bufferH1.get(pair);
        Deque<Candle> m15 = bufferM15.get(pair);
        
        if (h4 == null || h4.size() < 50 ||
            h1 == null || h1.size() < 100 ||
            m15 == null || m15.size() < 200) {
            return null; // Not enough data
        }
        
        // Calculate indicators
        TrendAnalysis h4Trend = analyzeTrend(h4, "H4");
        MomentumAnalysis h1Momentum = analyzeMomentum(h1, "H1");
        EntryAnalysis m15Entry = analyzeEntry(m15, "M15");
        
        // Calculate probabilities
        double probBull = 0;
        double probBear = 0;
        String recommendation = "HOLD";
        String insight = "";
        
        // H4 trend weight: 40%
        if (h4Trend.direction > 0) {
            probBull += 40;
            insight += "H4 Bullish trend. ";
        } else if (h4Trend.direction < 0) {
            probBear += 40;
            insight += "H4 Bearish trend. ";
        } else {
            probBull += 20;
            probBear += 20;
            insight += "H4 Neutral. ";
        }
        
        // H1 momentum weight: 35%
        if (h1Momentum.rsi > 55 && h1Momentum.macdSignal > 0) {
            probBull += 35;
            insight += "H1 Bullish momentum. ";
        } else if (h1Momentum.rsi < 45 && h1Momentum.macdSignal < 0) {
            probBear += 35;
            insight += "H1 Bearish momentum. ";
        } else {
            probBull += 17.5;
            probBear += 17.5;
            insight += "H1 Neutral momentum. ";
        }
        
        // M15 entry weight: 25%
        if (m15Entry.hasSignal) {
            if (m15Entry.isBullish) {
                probBull += 25;
                insight += "M15 Buy signal detected. ";
            } else {
                probBear += 25;
                insight += "M15 Sell signal detected. ";
            }
        } else {
            probBull += 12.5;
            probBear += 12.5;
            insight += "M15 No clear signal. ";
        }
        
        // Determine recommendation
        if (probBull >= 65) {
            recommendation = "BUY";
        } else if (probBear >= 65) {
            recommendation = "SELL";
        } else {
            recommendation = "HOLD";
        }
        
        // Calculate TP/SL
        double lastPrice = m15.peekLast().close;
        double atr = calculateATR(m15, 14);
        double tp, sl;
        
        if (recommendation.equals("BUY")) {
            tp = lastPrice + (atr * 2);
            sl = lastPrice - atr;
        } else if (recommendation.equals("SELL")) {
            tp = lastPrice - (atr * 2);
            sl = lastPrice + atr;
        } else {
            tp = lastPrice;
            sl = lastPrice;
        }
        
        return new AnalysisResult(
            pair.toUpperCase(),
            lastPrice,
            probBull,
            probBear,
            recommendation,
            tp,
            sl,
            insight.trim(),
            System.currentTimeMillis()
        );
    }
    
    // Trend Analysis Helper
    private TrendAnalysis analyzeTrend(Deque<Candle> candles, String timeframe) {
        List<Candle> list = new ArrayList<>(candles);
        double ema50 = calculateEMA(list, 50);
        double ema200 = calculateEMA(list, 200);
        
        int direction = 0;
        if (ema50 > ema200) direction = 1;
        else if (ema50 < ema200) direction = -1;
        
        return new TrendAnalysis(direction, ema50, ema200);
    }
    
    // Momentum Analysis Helper
    private MomentumAnalysis analyzeMomentum(Deque<Candle> candles, String timeframe) {
        List<Candle> list = new ArrayList<>(candles);
        double rsi = calculateRSI(list, 14);
        double macdSignal = calculateMACDSignal(list);
        
        return new MomentumAnalysis(rsi, macdSignal);
    }
    
    // Entry Analysis Helper
    private EntryAnalysis analyzeEntry(Deque<Candle> candles, String timeframe) {
        List<Candle> list = new ArrayList<>(candles);
        
        // Check for entry patterns
        boolean hasSignal = false;
        boolean isBullish = false;
        
        // Simple pattern detection (enhance this)
        if (list.size() >= 3) {
            Candle c1 = list.get(list.size() - 3);
            Candle c2 = list.get(list.size() - 2);
            Candle c3 = list.get(list.size() - 1);
            
            // Bullish engulfing
            if (c2.close < c2.open && c3.close > c3.open && 
                c3.close > c2.open && c3.open < c2.close) {
                hasSignal = true;
                isBullish = true;
            }
            
            // Bearish engulfing
            if (c2.close > c2.open && c3.close < c3.open &&
                c3.close < c2.open && c3.open > c2.close) {
                hasSignal = true;
                isBullish = false;
            }
        }
        
        return new EntryAnalysis(hasSignal, isBullish);
    }
    
    // Technical Indicators
    private double calculateEMA(List<Candle> candles, int period) {
        if (candles.size() < period) return 0;
        
        double multiplier = 2.0 / (period + 1);
        double ema = candles.get(0).close;
        
        for (int i = 1; i < candles.size(); i++) {
            ema = (candles.get(i).close - ema) * multiplier + ema;
        }
        
        return ema;
    }
    
    private double calculateRSI(List<Candle> candles, int period) {
        if (candles.size() < period + 1) return 50;
        
        double avgGain = 0;
        double avgLoss = 0;
        
        for (int i = candles.size() - period; i < candles.size(); i++) {
            double change = candles.get(i).close - candles.get(i - 1).close;
            if (change > 0) avgGain += change;
            else avgLoss += Math.abs(change);
        }
        
        avgGain /= period;
        avgLoss /= period;
        
        if (avgLoss == 0) return 100;
        double rs = avgGain / avgLoss;
        return 100 - (100 / (1 + rs));
    }
    
    private double calculateMACDSignal(List<Candle> candles) {
        double ema12 = calculateEMA(candles, 12);
        double ema26 = calculateEMA(candles, 26);
        return ema12 - ema26;
    }
    
    private double calculateATR(Deque<Candle> candles, int period) {
        List<Candle> list = new ArrayList<>(candles);
        if (list.size() < period + 1) return 0;
        
        double atr = 0;
        for (int i = list.size() - period; i < list.size(); i++) {
            Candle current = list.get(i);
            Candle previous = list.get(i - 1);
            
            double tr = Math.max(
                current.high - current.low,
                Math.max(
                    Math.abs(current.high - previous.close),
                    Math.abs(current.low - previous.close)
                )
            );
            atr += tr;
        }
        
        return atr / period;
    }
    
    // HTTP Server
    @Override
    public Response serve(IHTTPSession session) {
        // Add CORS headers
        Response response = handleRequest(session);
        response.addHeader("Access-Control-Allow-Origin", "*");
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        response.addHeader("Access-Control-Allow-Headers", "Content-Type");
        return response;
    }
    
    private Response handleRequest(IHTTPSession session) {
        String uri = session.getUri();
        
        if (uri.equals("/api/analyze")) {
            return handleAnalyzeRequest();
        } else if (uri.equals("/api/status")) {
            return handleStatusRequest();
        } else if (uri.equals("/") || uri.equals("/index.html")) {
            return serveDashboard();
        } else {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, 
                                         "text/plain", "Not Found");
        }
    }
    
    private Response handleAnalyzeRequest() {
        System.out.println("[INFO] Running analysis...");
        
        JsonObject result = new JsonObject();
        JsonArray buyArray = new JsonArray();
        JsonArray sellArray = new JsonArray();
        JsonArray holdArray = new JsonArray();
        
        for (String pair : topPairs) {
            AnalysisResult analysis = analyzePairMTF(pair);
            if (analysis != null) {
                JsonObject json = analysis.toJson();
                
                switch (analysis.recommendation) {
                    case "BUY":
                        buyArray.add(json);
                        break;
                    case "SELL":
                        sellArray.add(json);
                        break;
                    default:
                        holdArray.add(json);
                        break;
                }
                
                // Cache result
                analysisCache.put(pair, analysis);
            }
        }
        
        lastAnalysisTime = System.currentTimeMillis();
        
        result.add("buy", buyArray);
        result.add("sell", sellArray);
        result.add("hold", holdArray);
        result.addProperty("timestamp", lastAnalysisTime);
        result.addProperty("totalPairs", topPairs.size());
        
        System.out.println("[INFO] Analysis complete. Buy: " + buyArray.size() + 
                          ", Sell: " + sellArray.size() + ", Hold: " + holdArray.size());
        
        return newFixedLengthResponse(Response.Status.OK, 
                                     "application/json", gson.toJson(result));
    }
    
    private Response handleStatusRequest() {
        JsonObject status = new JsonObject();
        status.addProperty("serverRunning", true);
        status.addProperty("wsConnected", ws != null);
        status.addProperty("pairsTracked", topPairs.size());
        status.addProperty("lastAnalysis", lastAnalysisTime);
        
        JsonObject bufferSizes = new JsonObject();
        bufferSizes.addProperty("h4", bufferH4.size());
        bufferSizes.addProperty("h1", bufferH1.size());
        bufferSizes.addProperty("m15", bufferM15.size());
        status.add("buffers", bufferSizes);
        
        return newFixedLengthResponse(Response.Status.OK, 
                                     "application/json", gson.toJson(status));
    }
    
    private Response serveDashboard() {
        String html = """
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Crypto Futures Analysis Dashboard</title>
    <style>
        * {
            margin: 0;
            padding: 0;
            box-sizing: border-box;
        }
        
        body {
            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Oxygen, Ubuntu, sans-serif;
            background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
            min-height: 100vh;
            padding: 20px;
        }
        
        .container {
            max-width: 1400px;
            margin: 0 auto;
        }
        
        .header {
            background: white;
            border-radius: 15px;
            padding: 20px;
            margin-bottom: 20px;
            box-shadow: 0 10px 30px rgba(0,0,0,0.1);
        }
        
        .header h1 {
            color: #333;
            font-size: 24px;
            margin-bottom: 10px;
        }
        
        .status {
            display: flex;
            gap: 20px;
            flex-wrap: wrap;
        }
        
        .status-item {
            display: flex;
            align-items: center;
            gap: 5px;
        }
        
        .status-indicator {
            width: 10px;
            height: 10px;
            border-radius: 50%;
            background: #4CAF50;
        }
        
        .status-indicator.offline {
            background: #f44336;
        }
        
        .controls {
            background: white;
            border-radius: 15px;
            padding: 20px;
            margin-bottom: 20px;
            box-shadow: 0 10px 30px rgba(0,0,0,0.1);
            display: flex;
            justify-content: space-between;
            align-items: center;
            flex-wrap: wrap;
            gap: 10px;
        }
        
        .btn {
            background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
            color: white;
            border: none;
            padding: 12px 30px;
            border-radius: 8px;
            font-size: 16px;
            font-weight: 600;
            cursor: pointer;
            transition: transform 0.2s;
        }
        
        .btn:hover {
            transform: translateY(-2px);
        }
        
        .btn:disabled {
            opacity: 0.5;
            cursor: not-allowed;
        }
        
        .tabs {
            background: white;
            border-radius: 15px;
            padding: 10px;
            margin-bottom: 20px;
            box-shadow: 0 10px 30px rgba(0,0,0,0.1);
            display: flex;
            gap: 5px;
        }
        
        .tab {
            flex: 1;
            padding: 12px;
            background: transparent;
            border: none;
            border-radius: 8px;
            font-size: 16px;
            font-weight: 600;
            cursor: pointer;
            transition: all 0.3s;
            position: relative;
        }
        
        .tab.active {
            background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
            color: white;
        }
        
        .tab .badge {
            position: absolute;
            top: 5px;
            right: 10px;
            background: rgba(0,0,0,0.2);
            color: white;
            border-radius: 10px;
            padding: 2px 8px;
            font-size: 12px;
        }
        
        .content {
            background: white;
            border-radius: 15px;
            padding: 20px;
            box-shadow: 0 10px 30px rgba(0,0,0,0.1);
            overflow-x: auto;
        }
        
        table {
            width: 100%;
            border-collapse: collapse;
        }
        
        th {
            background: #f5f5f5;
            padding: 12px;
            text-align: left;
            font-weight: 600;
            color: #666;
            border-bottom: 2px solid #ddd;
        }
        
        td {
            padding: 12px;
            border-bottom: 1px solid #eee;
        }
        
        tr:hover {
            background: #f9f9f9;
        }
        
        .buy-row {
            background: #e8f5e9;
        }
        
        .sell-row {
            background: #ffebee;
        }
        
        .hold-row {
            background: #fff9c4;
        }
        
        .prob-high {
            color: #4CAF50;
            font-weight: 600;
        }
        
        .prob-low {
            color: #f44336;
            font-weight: 600;
        }
        
        .loading {
            text-align: center;
            padding: 40px;
            color: #666;
        }
        
        .spinner {
            border: 3px solid #f3f3f3;
            border-top: 3px solid #667eea;
            border-radius: 50%;
            width: 40px;
            height: 40px;
            animation: spin 1s linear infinite;
            margin: 20px auto;
        }
        
        @keyframes spin {
            0% { transform: rotate(0deg); }
            100% { transform: rotate(360deg); }
        }
        
        .filter-controls {
            display: flex;
            gap: 10px;
            align-items: center;
        }
        
        .filter-controls label {
            font-size: 14px;
            color: #666;
        }
        
        .filter-controls select {
            padding: 8px;
            border: 1px solid #ddd;
            border-radius: 5px;
        }
        
        @media (max-width: 768px) {
            .header h1 {
                font-size: 20px;
            }
            
            .tabs {
                flex-direction: column;
            }
            
            table {
                font-size: 14px;
            }
            
            td, th {
                padding: 8px;
            }
        }
    </style>
</head>
<body>
    <div class="container">
        <div class="header">
            <h1>🚀 Crypto Futures Analysis Dashboard</h1>
            <div class="status">
                <div class="status-item">
                    <div class="status-indicator" id="serverStatus"></div>
                    <span>Server</span>
                </div>
                <div class="status-item">
                    <div class="status-indicator" id="wsStatus"></div>
                    <span>WebSocket</span>
                </div>
                <div class="status-item">
                    <span id="pairsCount">0 pairs tracked</span>
                </div>
                <div class="status-item">
                    <span id="lastUpdate">Last update: Never</span>
                </div>
            </div>
        </div>
        
        <div class="controls">
            <button class="btn" onclick="runAnalysis()" id="analyzeBtn">
                🔍 ANALYZE NOW
            </button>
            <div class="filter-controls">
                <label>Show top:</label>
                <select id="topFilter" onchange="applyFilter()">
                    <option value="5">5</option>
                    <option value="10" selected>10</option>
                    <option value="25">25</option>
                    <option value="50">All</option>
                </select>
            </div>
        </div>
        
        <div class="tabs">
            <button class="tab active" onclick="showTab('buy')" id="buyTab">
                📈 BUY <span class="badge" id="buyCount">0</span>
            </button>
            <button class="tab" onclick="showTab('sell')" id="sellTab">
                📉 SELL <span class="badge" id="sellCount">0</span>
            </button>
            <button class="tab" onclick="showTab('hold')" id="holdTab">
                ⏸️ RANGING <span class="badge" id="holdCount">0</span>
            </button>
        </div>
        
        <div class="content" id="content">
            <div class="loading">
                <p>Ready to analyze</p>
                <p style="margin-top: 10px; font-size: 14px; color: #999;">
                    Click "ANALYZE NOW" to start
                </p>
            </div>
        </div>
    </div>
    
    <script>
        let currentTab = 'buy';
        let analysisData = null;
        let topFilter = 10;
        
        // Check server status on load
        checkStatus();
        setInterval(checkStatus, 30000);
        
        function checkStatus() {
            fetch('/api/status')
                .then(res => res.json())
                .then(data => {
                    document.getElementById('serverStatus').classList.toggle('offline', !data.serverRunning);
                    document.getElementById('wsStatus').classList.toggle('offline', !data.wsConnected);
                    document.getElementById('pairsCount').textContent = data.pairsTracked + ' pairs tracked';
                    
                    if (data.lastAnalysis > 0) {
                        const date = new Date(data.lastAnalysis);
                        document.getElementById('lastUpdate').textContent = 
                            'Last update: ' + date.toLocaleTimeString();
                    }
                })
                .catch(err => {
                    document.getElementById('serverStatus').classList.add('offline');
                    document.getElementById('wsStatus').classList.add('offline');
                });
        }
        
        function runAnalysis() {
            const btn = document.getElementById('analyzeBtn');
            btn.disabled = true;
            btn.textContent = '⏳ Analyzing...';
            
            document.getElementById('content').innerHTML = `
                <div class="loading">
                    <div class="spinner"></div>
                    <p>Running MTF analysis on all pairs...</p>
                </div>
            `;
            
            fetch('/api/analyze')
                .then(res => res.json())
                .then(data => {
                    analysisData = data;
                    updateCounts();
                    renderResults();
                    btn.disabled = false;
                    btn.textContent = '🔍 ANALYZE NOW';
                    
                    // Update last update time
                    const date = new Date(data.timestamp);
                    document.getElementById('lastUpdate').textContent = 
                        'Last update: ' + date.toLocaleTimeString();
                })
                .catch(err => {
                    console.error('Analysis error:', err);
                    document.getElementById('content').innerHTML = `
                        <div class="loading">
                            <p style="color: #f44336;">❌ Analysis failed</p>
                            <p style="margin-top: 10px; font-size: 14px; color: #999;">
                                ${err.message}
                            </p>
                        </div>
                    `;
                    btn.disabled = false;
                    btn.textContent = '🔍 ANALYZE NOW';
                });
        }
        
        function updateCounts() {
            if (!analysisData) return;
            
            document.getElementById('buyCount').textContent = analysisData.buy?.length || 0;
            document.getElementById('sellCount').textContent = analysisData.sell?.length || 0;
            document.getElementById('holdCount').textContent = analysisData.hold?.length || 0;
        }
        
        function showTab(tab) {
            currentTab = tab;
            
            // Update tab styles
            document.querySelectorAll('.tab').forEach(t => t.classList.remove('active'));
            document.getElementById(tab + 'Tab').classList.add('active');
            
            renderResults();
        }
        
        function applyFilter() {
            topFilter = parseInt(document.getElementById('topFilter').value);
            renderResults();
        }
        
        function renderResults() {
            if (!analysisData) return;
            
            let data = analysisData[currentTab] || [];
            
            // Sort by probability
            if (currentTab === 'buy') {
                data.sort((a, b) => b.probBull - a.probBull);
            } else if (currentTab === 'sell') {
                data.sort((a, b) => b.probBear - a.probBear);
            }
            
            // Apply filter
            if (topFilter < 50) {
                data = data.slice(0, topFilter);
            }
            
            if (data.length === 0) {
                document.getElementById('content').innerHTML = `
                    <div class="loading">
                        <p>No ${currentTab} signals found</p>
                    </div>
                `;
                return;
            }
            
            let html = `
                <table>
                    <thead>
                        <tr>
                            <th>Pair</th>
                            <th>Price</th>
                            <th>Bull %</th>
                            <th>Bear %</th>
                            <th>Action</th>
                            <th>TP</th>
                            <th>SL</th>
                            <th>Insight</th>
                        </tr>
                    </thead>
                    <tbody>
            `;
            
            data.forEach(item => {
                const rowClass = currentTab === 'buy' ? 'buy-row' : 
                               currentTab === 'sell' ? 'sell-row' : 'hold-row';
                
                html += `
                    <tr class="${rowClass}">
                        <td><strong>${item.pair}</strong></td>
                        <td>$${item.lastPrice.toFixed(4)}</td>
                        <td class="${item.probBull > 60 ? 'prob-high' : ''}">${item.probBull.toFixed(1)}%</td>
                        <td class="${item.probBear > 60 ? 'prob-high' : ''}">${item.probBear.toFixed(1)}%</td>
                        <td><strong>${item.recommendation}</strong></td>
                        <td>$${item.tp.toFixed(4)}</td>
                        <td>$${item.sl.toFixed(4)}</td>
                        <td style="font-size: 12px;">${item.insight}</td>
                    </tr>
                `;
            });
            
            html += `
                    </tbody>
                </table>
            `;
            
            document.getElementById('content').innerHTML = html;
        }
    </script>
</body>
</html>
        """;
        
        return newFixedLengthResponse(Response.Status.OK, "text/html", html);
    }
    
    // Helper Classes
    static class Candle {
        long timestamp;
        double open, high, low, close, volume;
        
        Candle(long timestamp, double open, double high, double low, double close, double volume) {
            this.timestamp = timestamp;
            this.open = open;
            this.high = high;
            this.low = low;
            this.close = close;
            this.volume = volume;
        }
    }
    
    static class AnalysisResult {
        String pair;
        double lastPrice;
        double probBull;
        double probBear;
        String recommendation;
        double tp;
        double sl;
        String insight;
        long timestamp;
        
        AnalysisResult(String pair, double lastPrice, double probBull, double probBear,
                      String recommendation, double tp, double sl, String insight, long timestamp) {
            this.pair = pair;
            this.lastPrice = lastPrice;
            this.probBull = probBull;
            this.probBear = probBear;
            this.recommendation = recommendation;
            this.tp = tp;
            this.sl = sl;
            this.insight = insight;
            this.timestamp = timestamp;
        }
        
        JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("pair", pair);
            json.addProperty("lastPrice", lastPrice);
            json.addProperty("probBull", probBull);
            json.addProperty("probBear", probBear);
            json.addProperty("recommendation", recommendation);
            json.addProperty("tp", tp);
            json.addProperty("sl", sl);
            json.addProperty("insight", insight);
            json.addProperty("timestamp", timestamp);
            return json;
        }
    }
    
    static class TrendAnalysis {
        int direction; // 1=bullish, -1=bearish, 0=neutral
        double ema50;
        double ema200;
        
        TrendAnalysis(int direction, double ema50, double ema200) {
            this.direction = direction;
            this.ema50 = ema50;
            this.ema200 = ema200;
        }
    }
    
    static class MomentumAnalysis {
        double rsi;
        double macdSignal;
        
        MomentumAnalysis(double rsi, double macdSignal) {
            this.rsi = rsi;
            this.macdSignal = macdSignal;
        }
    }
    
    static class EntryAnalysis {
        boolean hasSignal;
        boolean isBullish;
        
        EntryAnalysis(boolean hasSignal, boolean isBullish) {
            this.hasSignal = hasSignal;
            this.isBullish = isBullish;
        }
    }
    
    // Main method
    public static void main(String[] args) {
        try {
            new CryptoAnalysisServer();
            
            // Keep running
            while (true) {
                Thread.sleep(1000);
            }
        } catch (Exception e) {
            System.err.println("[FATAL] Server failed to start: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
