# Uygulama Sprint Planı (Execution Roadmap)
**Proje:** Bağlantılı Araç Çarpışma Algılama (eCall) ve Otomatik Hasar/Asistans Orkestratörü  
**Plan Durumu:** `FROZEN (Adversarial İnceleme Sonrası Onaylı)`

---

## Sprint 1: Çekirdek Veri Karşılama, DB Optimizasyonu ve Transactional Outbox
**Hedef:** Araçtan gelen eCall MSD paketlerini doğrulayan, eşzamanlı mükerrer (duplicate) istekleri DB seviyesinde engelleyen ve tek bir transaction içinde hem kaza kaydını hem de Outbox olayını kaydeden temel omurgayı kurmak.

### Çıktılar (Deliverables)
- Maven / Spring Boot 3.x (Java 17) proje iskeleti ve bağımlılıkları.
- `V1__init_ecall_schema.sql`: `ecall_incidents` ve `outbox_events` tabloları, `UNIQUE` kısıtları, `(vin, crash_timestamp)` bileşik indeksi ve Outbox partial/composite indeksleri.
- `SeverityCalculator`: Darbe hızı ($\Delta v$), airbag ve takla verisine göre deterministik sınıflandırma (`CRITICAL`, `HIGH`, `MODERATE`).
- `EcallIngestionService` & `POST /api/v1/ecall/events`: Idempotency kontrolü, Optimistic Locking (`@Version`) ve atomik Outbox yazımı.

### Otomatik Testler & Ölçüm Metrikleri (Exit Criteria)
| Metrik / Test | Hedef Değer | Doğrulama Yöntemi |
| :--- | :--- | :--- |
| **Severity Sınıflandırma Doğruluğu** | %100 | `SeverityCalculatorTest` (Sınır değer analizli birim testler) |
| **Eşzamanlı Idempotency (Race Condition)** | 20 paralel thread aynı `eventId` ile saldırdığında DB'de **tam 1 kayıt** oluşmalı | `EcallIngestionConcurrencyTest` (`CountDownLatch` + `ExecutorService`) |
| **Atomik Outbox Garantisi** | Kayıt yapılan her `ecall_incidents` satırı için `outbox_events` tablosunda **1 adet `PENDING`** kayıt bulunmalı | Entegrasyon testi doğrulaması |

---

## Sprint 2: Hata Toleranslı (Resilient) Asenkron Orkestrasyon, FNOL ve Çekici Atama
**Hedef:** Outbox tablosundaki bekleyen kazaları `SKIP LOCKED` semantiği ile okuyup dış Sigorta (Poliçe/FNOL) ve Yol Yardım (Asistans/Çekici) servislerini Resilience4j (Circuit Breaker + Fallback) korumasında çağırmak.

### Çıktılar (Deliverables)
- `IncidentEventPublisher` arayüzü ve 2 adaptör (`LocalAsyncIncidentEventPublisher` ve `KafkaIncidentEventPublisher`).
- `OutboxRelayScheduler`: Bekleyen kayıtları gruplar halinde çekip ilgili yayınlayıcıya aktaran ve hata durumunda `retry_count` + `next_attempt_at` (Exponential Backoff) güncelleyen mekanizma.
- `PolicyVerificationPort` & `RoadsideDispatchPort` arayüzleri ve dış servis istemcileri.
- `IncidentOrchestrationService`:
  - Normal akışta poliçeyi doğrular, çekici atar ve FNOL hasar dosyasını açar (`DISPATCHED_COMPLETE`).
  - Sigorta servisi çöktüğünde veya yavaşladığında **Circuit Breaker** devreye girer; `CRITICAL` ve `HIGH` kazalarda insan güvenliği gereği poliçe yanıtını beklemeden acil çekici/asistans atar (`DISPATCHED_FALLBACK`).

### Otomatik Testler & Ölçüm Metrikleri (Exit Criteria)
| Metrik / Test | Hedef Değer | Doğrulama Yöntemi |
| :--- | :--- | :--- |
| **Normal Orkestrasyon Akışı** | `RECEIVED` -> `DISPATCHED_COMPLETE` geçişi < 500ms | `IncidentOrchestrationIntegrationTest` |
| **Circuit Breaker & Fallback Koruması** | Sigorta servisi %100 hata (`HTTP 503`) dönerken `CRITICAL` kazaların **%100'üne** çekici atanmalı (`DISPATCHED_FALLBACK`) | Kaos / Hata enjeksiyonlu entegrasyon testi |
| **DLQ / Manual Review Geçişi** | Maksimum deneme sayısını aşan geçersiz kayıtların `MANUAL_REVIEW_REQUIRED` durumuna geçmesi | Birim ve entegrasyon testi |

---

## Sprint 3: Gerçekçi İstemci (TCU) Simülatörü, Kaos Enjeksiyon API'si ve CI/CD Pipeline
**Hedef:** Gerçek bir araç filosundan gelen eCall trafiğini (mükerrer paketler, zincirleme kaza patlamaları) ve dış sigorta sisteminin çöküşünü simüle edebilen istemci/simülasyon modülünü ve GitHub Actions CI hattını kurmak.

### Çıktılar (Deliverables)
- `SimulationAndChaosController` (`/api/v1/simulation/...`):
  - Dış sigorta servisine yapay gecikme (`artificialLatencyMs`) ve hata oranı (`failureRatePercent`) enjekte edebilen kontrol uçları.
  - Gerçekçi araç telemetri yükü (tekil kaza, mükerrer TCU tekrarı veya 500 araçlık fırtına/zincirleme kaza senaryosu) başlatabilen **TCU Traffic Simulator**.
- `docker-compose.yml` & `Dockerfile`: PostgreSQL, Kafka ve Prometheus/Grafana ile tek komutla ayağa kalkabilen üretim hazırlıklı altyapı.
- `.github/workflows/ci.yml`: Her push ve PR'da derleme, birim/entegrasyon testleri ve paketleme adımlarını çalıştıran GitHub Actions pipeline.

### Otomatik Testler & Ölçüm Metrikleri (Exit Criteria)
| Metrik / Test | Hedef Değer | Doğrulama Yöntemi |
| :--- | :--- | :--- |
| **Uçtan Uca Simülasyon Testi** | 100 araçlık simülasyonda (25'i mükerrer) tam 75 benzersiz olay işlenmeli, 25'i idempotent reddedilmeli | `SimulationEndToEndTest` |
| **CI Pipeline Geçişi** | Tüm test paketinin (`mvn test`) %100 başarıyla ve < 60sn içinde tamamlanması | Yerel ve CI koşumu |

---

## Sprint 4: Lokal Yüksek Yük PoC, Kurumsal Test Raporu ve Mühendis İmzalı `README.md`
**Hedef:** Çalışan sunucu üzerinde gerçek yüksek trafik ve kaos testlerini koşturmak, gerçek ölçüm sonuçlarını (p50, p95, p99 gecikme, throughput, DB sorgu süreleri) toplamak ve bunları kurumsal standartta raporlamak.

### Çıktılar (Deliverables)
- Canlı sunucu üzerinde koşturulan yüksek yük & kaos PoC senaryoları.
- `docs/POC_ARCHITECTURE_VALIDATION.md`: Mimari kararların (Outbox, Idempotency, Circuit Breaker, İndeksler) yük altında nasıl davrandığını kanıtlayan PoC belgesi.
- `docs/TEST_CONDITIONS_AND_RESULTS.md`: Donanım/JVM ortamı, test parametreleri, senaryo bazlı ham sonuç tabloları ve darboğaz analizini içeren resmi referans test raporu.
- `README.md`: AI klişelerinden tamamen arındırılmış, dürüst, mimari kararları ve bilinen kısıtları açıkça anlatan, kodla %100 uyumlu ana doküman.
