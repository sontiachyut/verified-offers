# Search and claim baseline evaluation

Measured 2026-09-16 using the code now recorded in `490e714`, Java 21, local
macOS arm64, two effective Java processors, and a single OpenSearch 3.8.0
container with 512MiB heap. Synthetic data only. This is quality evaluation, not
an API throughput or production cost benchmark.

| Baseline | Dataset | Result |
| --- | --- | --- |
| OpenSearch title BM25 | 24 offers, 12 graded queries | mean nDCG@10 **0.87649**, mean Recall@10 **0.875** |
| Explicit USD extractor | 20 held-out texts (8 separate development examples) | precision **0.80**, recall **0.7273**, proposal coverage **0.50** |

All query rankings and extraction errors are retained in
[ranking results](validation/p5-ranking.json) and
[claim results](validation/p5-claims.json). No paid model calls were made; inference
API spend was $0. Local CPU, electricity and engineering time are not zero-cost.
Recorded extraction nanoseconds are individual un-warmed micro-observations, not
reliable latency percentiles.

Labels were separately authored in `evaluation/` before running the baselines,
not derived from returned rankings or verifier decisions. They are project-authored
synthetic judgments, not independent external human annotations. The sample is
too small for broad quality claims. Unjudged items count as nonrelevant; graded
gain is `2^grade-1`, log2 discount, cutoff 10; Recall counts grades above zero.
Tie ordering uses the production PIT adapter. Source freshness is not a label.

## Failure analysis and decision

BM25 missed the hub for “laptop docking station” (vocabulary mismatch), preferred
a webcam cover over a webcam (accessory ambiguity), and missed a secondary
charger judgment. These are targets for a future semantic retrieval comparison;
no unmeasured model is claimed to improve them.

The extractor missed whole dollars, comma-separated thousands and a USD suffix.
Two false proposals interpreted a discount and a bundle total as unit prices.
Therefore extraction is **experimental and disabled by default**; it is a
monetary-mention baseline, not semantic price understanding. Do not market its
80% precision as ready for autonomous decisions. A future parser/model needs
fresh held-out labels and a precision/safety gate, not repeated tuning to these
cases. Existing verification blocks wrong source prices, but cannot prove text
semantics if a wrong interpretation coincidentally equals the source price.

## Optional endpoint

Enable `--offers.claims.enabled=true`. POST `/api/v1/claims/extract` with
`tenantId`, `merchantId`, `offerId`, `text` (1–2000 characters). Secured mode
requires `offers:read` and the token's tenant. Response contains extraction
status/reason, optional proposed price and exact UTF-16 text span, and optional
authoritative verification. `PROPOSED` is never `VERIFIED` by itself. Text cannot
change identity, facts, stock or freshness, or perform writes. Do not display
text as HTML. No model, URL fetch or tool execution is involved.

## Reproduce

```sh
./mvnw -Dtest=ClaimExtractorTest,ClaimApiTest,ClaimEvaluationTest,EvaluationMetricsTest \
  -Dit.test=RankingEvaluationIT verify
```

Docker is mandatory for the actual-index ranking test. Generated raw reports go
to `target/evaluation/`; failures are not skipped. Full `./mvnw verify` also runs
these and the system regression. Preserve dataset, commit and environment for
future comparisons. Paid model/provider integration requires separate approval.
