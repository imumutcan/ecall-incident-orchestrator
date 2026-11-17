# Test Koşulları, Yük Senaryoları ve Ölçüm Sonuçları Raporu

**Doküman No:** `QA-REP-ECALL-2026-01`  
**İlgili Mimari PoC Belgesi:** [POC_ARCHITECTURE_VALIDATION.md](POC_ARCHITECTURE_VALIDATION.md)  
**Test Koşum Sınıfı:** [`LocalHighLoadPocBenchmarkTest.java`](../src/test/java/com/automotive/ecall/poc/LocalHighLoadPocBenchmarkTest.java)  
**Ham Veri Kaynağı:** `target/poc-benchmark-results.json`

---

## 1. Test Ortamı ve Donanım/JVM Koşulları (Test Environment)

Tüm testler harici ağ parazitinden arındırılmış lokal ortamda, tekrarlanabilir (reproducible) JUnit 5 + Spring Boot Test koşucusu üzerinde gerçekleştirilmiştir:

| Bileşen | Değer / Yapılandırma |
| :--- | :--- |
| **İşletim Sistemi & Mimari** | macOS (aarch64 / Apple Silicon) |
| **Java Çalışma Zamanı (JDK)** | Eclipse Adoptium OpenJDK `17.0.20.1+1` (64-Bit Server VM) |
| **Uygulama Çatısı** | Spring Boot `3.3.6`, Hibernate ORM, HikariCP (`max-pool-size: 30`) |
| **Veritabanı Motoru (Lokal PoC)** | H2 `2.2.224` (`MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH`) |
| **Dayanıklılık (Resilience) Ayarı** | Resilience4j `2.3.0` (`slidingWindowSize: 10`, `minimumNumberOfCalls: 5`, `failureRateThreshold: 50%`) |
| **JVM Isınma (Warm-up)** | Ölçüm öncesi 40 paketlik (30 tekil + 10 mükerrer) ısınma turu koşturulup tablolar temizlenmiştir. |

---

## 2. Birim ve Entegrasyon Test Paketi Sonuçları

Komut: `mvn test`  
Toplam Koşulan Test: **12 Test, 0 Hata (Failure), 0 Kusur (Error)**

| Test Sınıfı | Kapsanan Senaryo | Sonuç |
| :--- | :--- | :--- |
| [`SeverityCalculatorTest`](../src/test/java/com/automotive/ecall/service/SeverityCalculatorTest.java) (5 Test) | EN 15722 $\Delta v$ sınır değerleri (`15.0`, `25.0`, `39.9`, `40.0` km/s), airbag ve rollover kombinasyonları | **GEÇTİ (PASS)** |
| [`EcallIngestionConcurrencyTest`](../src/test/java/com/automotive/ecall/service/EcallIngestionConcurrencyTest.java) (1 Test) | `CountDownLatch` ile aynı milisaniyede serbest bırakılan **20 paralel thread**'in aynı `eventId` ile saldırısı (1 DB satırı + 19 idempotent yanıt) | **GEÇTİ (PASS)** |
| [`IncidentOrchestrationIntegrationTest`](../src/test/java/com/automotive/ecall/service/IncidentOrchestrationIntegrationTest.java) (3 Test) | 1. Sağlıklı poliçe + FNOL + çekici akışı<br>2. Sigorta çöküşünde Circuit Breaker `OPEN` ve `DISPATCHED_FALLBACK`<br>3. `MODERATE` kazada 3 deneme sonunda `MANUAL_REVIEW_REQUIRED` ve `FAILED_DLQ` | **GEÇTİ (PASS)** |
| [`SimulationEndToEndTest`](../src/test/java/com/automotive/ecall/api/SimulationEndToEndTest.java) (2 Test) | MockMvc üzerinden 100 paketlik (75 araç + 25 mükerrer) filo simülasyonu ve RFC 7807 `ProblemDetail` validasyon reddi | **GEÇTİ (PASS)** |
| [`LocalHighLoadPocBenchmarkTest`](../src/test/java/com/automotive/ecall/poc/LocalHighLoadPocBenchmarkTest.java) (1 Test) | Senaryo A (650 paket) ve Senaryo B (360 paket + Kaos Enjeksiyonu) yüksek yük ölçümü | **GEÇTİ (PASS)** |

---

## 3. Yüksek Yük ve Kaos PoC Ölçüm Sonuçları

### 3.1. Senaryo A: Yüksek Trafikli Bölgesel Kaza + %30 Hücresel Mükerrer Paket Fırtınası
* **Koşullar:** 500 benzersiz araç (Mercedes-Benz `W1K` ve Renault `VF1` şasi önekli) + 150 mükerrer TCU tekrarı = **650 toplam paket**, **24 eşzamanlı istemci thread'i**, dış servisler sağlıklı (`0 ms` yapay gecikme, `%0` hata).

| Ölçüm Metriği | SLA / Hedef | Ölçülen Gerçek Değer | Durum |
| :--- | :--- | :--- | :--- |
| **Toplam Gönderilen Paket** | 650 | **650** | Doğrulandı |
| **Yeni Kaydedilen Kaza (`202 Accepted`)** | 500 | **500** | %100 Doğruluk |
| **Engellenen Mükerrer Paket (`200 OK`)** | 150 | **150** | %100 Yakalama |
| **Ingestion Hata Sayısı (`5xx`)** | 0 | **0** | Sıfır Hata |
| **Toplam Karşılama Süresi (Wall Time)** | < 1000 ms | **240.85 ms** | Başarılı |
| **Karşılama Hızı (Throughput)** | > 1000 req/sn | **2,698.79 req/sn** | Başarılı |
| **Gecikme Medyanı (`p50 Latency`)** | < 15 ms | **4.74 ms** | Başarılı |
| **Gecikme `p95 Latency`** | < 60 ms | **22.92 ms** | Başarılı |
| **Gecikme `p99 Latency`** | < 100 ms | **28.18 ms** | Başarılı |
| **Maksimum Gecikme (`Max Latency`)** | - | **33.64 ms** | Başarılı |
| **Tamamlanan FNOL + Çekici (`DISPATCHED_COMPLETE`)** | 500 | **500** | Sıfır Kayıp |

---

### 3.2. Senaryo B: Dış Sigorta Servisi Çöküşü (150ms Gecikme + %100 HTTP 503 Hatası)
* **Koşullar:** 300 benzersiz araç (250 `CRITICAL`, 50 `MODERATE`) + 60 mükerrer TCU tekrarı = **360 toplam paket**, **24 eşzamanlı istemci thread'i**. Dış Sigorta Poliçe API'sine **150 ms yapay gecikme** ve **%100 `HTTP 503 Service Unavailable`** hatası enjekte edilmiştir.

| Ölçüm Metriği | Beklenen Davranış | Ölçülen Gerçek Değer | Mimari Yorum |
| :--- | :--- | :--- | :--- |
| **Yeni Kaydedilen Kaza** | 300 | **300** | Dış servis çökse de veri kaybı 0 |
| **Engellenen Mükerrer Paket** | 60 | **60** | Idempotency etkilenmedi |
| **Karşılama Hızı (Throughput)** | > 1000 req/sn | **3,864.57 req/sn** | Outbox ayrıştırması sayesinde tam hız |
| **Ingestion `p50 / p95 / p99 Latency`** | < 60 ms | **2.36 ms / 15.72 ms / 18.22 ms** | 150ms dış gecikme Ingestion'a hiç yansımadı |
| **Circuit Breaker Durumu** | `OPEN` | **`OPEN`** | 5 hatadan sonra devreyi açıp 45sn blokajı önledi |
| **Acil Fallback Çekici (`DISPATCHED_FALLBACK`)** | 250 (`CRITICAL`) | **250** | Tüm kritik kazalara poliçe beklenmeden çekici atandı |
| **Ertelenen (`RETRY_SCHEDULED`)** | 50 (`MODERATE`) | **50** | Orta şiddetli kazalar backoff ile Outbox'ta tutuldu |

---

## 4. Darboğaz Analizi ve Gözlemler (Bottleneck Analysis)

1. **HikariCP Bağlantı Havuzu ve `PROPAGATION_REQUIRES_NEW` Etkisi:**
   - 24 eşzamanlı thread aynı anda yazma yaptığında, `EcallIngestionService` içindeki `requiresNewTransactionTemplate` her yazma için havuzdan 1 bağlantı alır. Havuz boyutu (`maximum-pool-size: 30`) eşzamanlı thread sayısından (24) büyük olduğu için bağlantı bekleme darboğazı yaşanmamıştır (`p95 = 22.92 ms`). Ancak 100+ eşzamanlı thread'de bağlantı havuzu doygunluğa ulaşacağı için Ingestion katmanında `open-in-view: false` tutulması ve transaction süresinin sadece DB `INSERT` süresiyle (~2-4 ms) sınırlandırılması hayati önem taşımaktadır.
2. **Outbox Tekil Kayıt Güncelleme Maliyeti:**
   - `OutboxRelayScheduler` her döngüde `LIMIT 100` kayıt çekip `FOR UPDATE SKIP LOCKED` ile kilitlemekte, ancak işlenen her kaydı döngü içinde `save()` etmektedir. 500 kaydın tamamının işlenmesi lokal bellekte ~120 ms sürmüştür; gerçek ağ üzerindeki bir PostgreSQL sunucusunda network round-trip maliyetini düşürmek için JDBC batch update (`rewriteBatchedStatements`) bir sonraki optimizasyon adımı olmalıdır.
