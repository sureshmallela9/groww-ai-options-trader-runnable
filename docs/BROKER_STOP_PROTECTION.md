# Broker stop protection — 11 November 2026

Observed Groww rejection: SL_M orders with price 0 and a positive trigger returned
16448 (limit/trigger difference beyond permissible range). The live equity MIS
path now uses SL stop-limit orders with a nonzero limit one verified tick beyond
the stop trigger: sell limit below trigger, buy-cover limit above trigger.

Independent sizing includes that extra tick in planned stop risk. After a broker
stop is observed waiting for its trigger, a transition to OPEN, or a partial fill,
converts the same stop order's remaining quantity to MARKET. Normal app stop,
target and deadline exits also modify that same order; they do not race a second
exit. Broker rejection still queues the existing emergency market exit.

A protection failure pauses automatic new entries for that run. Risk monitoring
and exits continue. A subsequent explicit Start creates a new reviewed run; a
protection failure cannot repeatedly recycle into new entries within one run.

Stop-limit orders can remain unfilled during price gaps or app/connectivity
failure. Market conversion requires the app and broker connection. A stop or
configured loss threshold does not guarantee an execution price or maximum loss.
