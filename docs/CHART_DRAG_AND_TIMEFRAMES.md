# Candle chart dragging and timeframes

Open Automation and connect the workspace. Each shortlisted stock has its own candle timeframe, zoom and history controls.

- Drag right to see older candles; drag left to return toward newer candles.
- Focus the chart and use Left/Right arrow keys to move through history. Home moves to the oldest available window; End returns to the latest.
- Zoom in/out changes how many chart candles are visible. Older/Newer buttons remain available. Reset view returns to the default chart range for the selected timeframe.
- Choose 5 minutes, 15 minutes, 30 minutes, 1 hour or 1 day. Date and time ticks use IST. The range above the chart identifies the first and last visible candle starts.
- Blue BUY and purple SELL markers remain actual saved paper fills. Amber diamonds remain pending requests. Changing timeframe never invents an order or a fill price.

## Data meaning

The application currently provides five-minute source candles. Larger chart timeframes group those records: first open, highest high, lowest low, last close and summed volume. Records group separately for each IST day, aligned to 09:15 for the regular-session display convention. The last hourly group is clipped at the regular 15:30 close. This convention does not determine exchange holidays or authorize trading.

Missing, incomplete or irregular source coverage is labelled partial and has a dashed candle border. No missing bars are fabricated. Daily completeness is checked against 75 regular-session five-minute slots; special sessions or off-session input remain labelled partial. Tooltips show the source count and expected coverage.

AI still receives the full original five-minute history. The chart timeframe is a display setting. AI RSI/VWAP labels explicitly retain the 5m source timeframe. Candle OHLCV and the expanded visible-candle table follow the chart view. CSV download continues exporting all original five-minute source records.

## Implementation

`candle-view.js` handles aggregation, time ticks and pointer/keyboard panning. `shortlist-charts.js` renders the selected view and preserves each stock's settings. Pointer capture keeps dragging active when the pointer leaves the plot. The SVG element remains mounted while candles redraw, and a shared interaction flag prevents periodic refreshes from replacing the chart during a drag. Chart focus is restored after redraws so keyboard panning can continue.

Historical windows use a candle timestamp as an anchor, rather than a moving offset. New data therefore does not push an inspected historical window forward. Pointer cancellation, lost capture, disconnection and leaving the screen release the interaction state.

The update touches static chart assets only. It adds no broker or AI endpoints and changes no strategy, order, risk or automation settings. No service restart is required.

## Verification — 5 November 2026

Unit/UI coverage includes chronological OHLCV aggregation, duplicate timestamps, missing slots, day boundaries, partial groups, final-session hourly groups, actual fill placement, IST axis ticks, continuous dragging, pointer cancellation, keyboard movement, timeframe persistence and unchanged source arrays/counts.

In the running app, saved ITC data was viewed as 15-minute candles, zoomed to 18 groups and dragged to older history. Keyboard movement then advanced that historical window. Daily zoom changed 11 displayed groups to five. In both cases the AI source count remained 756. This was a display test using cached 4 November data before the next session; it did not start a new monitoring schedule or place an order.
