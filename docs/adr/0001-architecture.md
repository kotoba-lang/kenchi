# ADR-0001: kenchi-actor — 多数の情報ソースを封じ込め、来歴で検証してから公開する全世界・不動産価値アクター設計

- Status: Accepted (2026-06-27)
- 関連: langgraph-clj ADR-0001 (Pregel superstep + interrupt + Datomic checkpoint), robotaxi-actor ADR-0001 (研究モデルを信頼境界に封じ込める同型), toritate (執帳) — did:web + PDS/Aozora 公開 + Murakumo-only 推論, kawasakijun ADR-0010 (Life Graph — EDN 事実層 + Datalog ビュー)
- 様式: robotaxi-actor と対をなす「contained node + independent governor + flywheel」設計

## 課題

全世界の不動産価値を**複数の情報ソースから分析し、公開する**アクターを設計したい。
しかし「物件の価値」は単一の真値として存在しない。**観測の雲**があるだけで、各ソースは
固有の被覆率・鮮度・通貨・方法論バイアス・**ライセンス（ToS）**を持ち、同じ場所に
対して平気で食い違う数字を返す:

1. 公的評価（米 county assessor / 日 地価公示・路線価 / EU 地籍）— 権威的だが遅延・課税バイアス。
2. 取引記録（登記 / 英 HM Land Registry / 日 取引価格情報）— 真値に近いが疎・離散。
3. ポータル/AVM（Zillow・Rightmove・SUUMO・Zestimate 等）— 新鮮だが**多くが再配布不可**・list≠sale。
4. 指数（Case-Shiller・OECD・BIS）— 地域トレンドであって per-parcel ではない。

したがって設計課題は「最良の AVM を選ぶ」ことではなく、**「各ソースを信頼境界の内側に
封じ込め、世界に公開する数字を *追跡可能・ライセンス清浄・不確実性に正直* にするには
どう層を被せるか」**である（robotaxi で AR1 を封じ込めたのと同型）。

## 決定

### 1. 各ソースは1つの IngestActor に封じ込め、直接公開させない

各外部ソースは IngestActor 内で *proposal*（stamped Observation）のみを返す**観測者**として
扱う。出力は必ず独立した `ProvenanceGovernor`（真実性）と `LicenseGovernor`（権利）を
通してから公開する。**単一の不変条件**:

> **kenchi は、ProvenanceGovernor が ≥N 独立・ライセンス清浄なソースに信頼区間付きで
> 辿れない点推定値を、決して公開しない。**

これが「単一ソースは事実でなく提案にすぎない」という制約を埋め、融合した数字を公の場で
擁護可能にする唯一の根拠。

### 2. ParcelActor = langgraph-clj StateGraph、1 run = 1 valuation

```
gather → resolve → fuse → govern → decide ─┬─ (≥N · license-clear · CI ok) ─▶ publish → END
                                           └─ (thin / outlier / ToS-block) ─▶ mrv → END
```

- `:observations` は SourceOrchestrator が**外部注入**。ParcelActor 自身は取得せず、
  与えられた観測を**融合するだけ**（グラフ内に無限ループを持たず、各 tick が監査可能・
  checkpoint 可能 — robotaxi の 1 run=1 tick と同型）。
- `govern` は融合エンジンと**別系統**。独立ソース数を再算定し、ライセンス行列を適用し、
  外れ値検査を行い、エンジンの点推定を**拒否して MRV 帯に落とせる**。
- `:audit` チャネルに全来歴集合・拒否・withhold を蓄積 → 監査・テイクダウン証拠・
  フライホイール再較正が同一ファクトログから落ちてくる（ADR-0010 同型）。

### 3. ProvenanceGovernor は融合エンジンと別系統（MRV フォールバック）

融合とは別経路で構築し、楽観や同一バグを共有しないようにする。

| 責務 | 機構 |
|---|---|
| N-source ゲート | `< N` 独立ソースの**点推定**は公開拒否（同一ベンダのミラーは1と数える） |
| ライセンス清浄 | ソース ToS ごとに `raw / derived-only / withhold`。寄与に derived-only が1つでもあれば公開も derived-only |
| 外れ値拒否 | ロバスト推定（median/Huber）。MAD 閾値超で乖離するソースを隔離・フラグ |
| 不確実性フロア | 必ず CI と `asof` を付す。証拠が薄れたら帯を広げる → MRV |
| 来歴 | 公開する各数字に `{source, obs-id, ts, weight}[]`（領収書）を同梱 |

N は高く始め（証拠が豊富な所だけ公開）、フライホイールが地域での較正を実証した範囲から
被覆を広げる（robotaxi の ODD 段階拡大と同型）。**MRV（Minimal Reliable Valuation）** =
証拠が薄い時に偽の精度の点を出さず、広い帯か `insufficient-evidence` を返す
（robotaxi の MRC＝安全停止の対応物）。

### 4. アクター・トポロジ（監督ツリー）

```
KenchiSystem (root supervisor)
├── SourceOrchestrator { IngestActor[S](sealed), LicenseGovernor, FreshnessActor }
├── ParcelActor[P]     { Resolver, ValuationEngine, ProvenanceGovernor, Ledger }
├── PublishActor       (cleared record → ATProto PDS / Aozora / lexicon)
├── ComplianceActor    (license registry・takedown・GDPR/個情・地域法ゲート)
├── QueryActor         (parcel / H3 / region / time の読み取り API)
└── ModelFlywheelActor (融合推定 vs 実現売買 のバックテスト → 重み再較正)
```

子の異常は親が MRV にフォールバック。公開可否ゲート（N・乖離・鮮度・較正 KPI）は
監査ログへの Datalog クエリで算出。

### 5. 公開面（このワークスペースへの着地）

- **同一性**: `did:web:…:com-junkawasaki-kenchi` + `.well-known/did.json`、
  `AtprotoPersonalDataServer` + `AozoraAppView`（toritate と同型）。
- **lexicon**: `com.junkawasaki.kenchi.valuation`
  `{parcel, h3, valueUsdMicros, ciLo/Hi, nSources, asof, license, provenance[]}`、
  集約 `…regionReport`（per-parcel が制限される地域でも H3 集約は公開可）。
- **Murakumo ノード**: kotoba WASM コンポーネントとして lattice auction で配置。
  `on-tick` 更新・`on-http` 照会・`kqe-assert!` で valuation を Datom ログへ。
  ライセンス制限入力は **Murakumo-only**（メッシュ外に出さない）・**derived-only** 公開
  （toritate の「Murakumo-only 推論 / 暗号化入力」天井と同型）。
- **天井**: ToS 制限ポータルは `DERIVED-ONLY`・per-parcel 公開不可地域は `AGGREGATE-ONLY`・
  **no PII**（parcel は場所であり人ではない）・地域法ゲート（GDPR / 個人情報保護法 / 地籍規則）。

### 6. 依存とポータビリティ

langgraph-clj（→ langchain-clj、ともに `.cljc`）の上に構築。JVM/SCI/CLJS/GraalVM 横断。
状態は checkpoint（dev: mem、prod: Datomic / kotoba Datom）。

## 帰結

- **実物（スキャフォールド）**: アクター・トポロジ、公開不変条件、N-source ゲート、
  ライセンス行列、外れ値拒否、MRV フォールバック、ロバスト融合＋CI、来歴トレイル。
  公開コントラクトは `test/kenchi/provenance_contract_test.clj` で実行可能を意図。
- **モック（本番差し替え点）**: `sources/fetch`→各ソース実 API（assessor/MLIT/registry/
  portal/index/OSM）、`resolve`→実エンティティ解決（H3/地籍突合）、FX 正規化→実レート、
  `publish`→実 PDS 書き込み。
- **未決事項**: ポータル/AVM の**商用再配布可否**（事業化の前提・最重要）、ソース信頼性
  prior の初期値、地域ごとの per-parcel 公開法規、実現売買による較正データの入手、
  通貨/タイムゾーン/地籍 ID の世界規模での正規化コスト。
