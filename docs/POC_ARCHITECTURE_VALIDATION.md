# Lokal PoC ve Mimari Yetkinlik Doğrulama Belgesi

**Proje:** Bağlantılı Araç Çarpışma Algılama (eCall) ve Otomatik Hasar/Asistans Orkestratörü  
**İlgili Referans Test Raporu:** [TEST_CONDITIONS_AND_RESULTS.md](TEST_CONDITIONS_AND_RESULTS.md)  
**Ham Ölçüm Çıktısı:** `target/poc-benchmark-results.json`

---

## 1. PoC'nin Amacı: Neden Bu Mimarileri Kurduk?

Bu kavram kanıtlama (PoC) çalışmasının amacı, otomotiv telemetri (Mercedes-Benz / Renault bağlantılı araç eCall sistemleri) ile sigorta hasar/asistans (FNOL) sistemlerinin kesişiminde yaşanan **3 temel üretim (production) darboğazını** lokal ortamda simüle etmek ve mimari çözümlerimizin davranışını somut metriklerle doğrulamaktır:

1. **Zayıf Şebeke ve Eşzamanlı Mükerrer Paket Fırtınası (TCU Duplicate Burst):**
   - Kaza anında araçtaki TCU modemi yanıt alamadığında aynı `eventId` değerine sahip paketi defalarca yeniden gönderir. Uygulama seviyesindeki basit bir `if (!existsByEventId)` kontrolü, aynı milisaniyede gelen paralel isteklerde *Race Condition* nedeniyle delinir.
2. **Dış Sigorta Servisi Çöküşü ve Zincirleme Kilitlenme (Cascading Failure):**
   - Bölgesel fırtına veya zincirleme kaza anında dış sigorta poliçe API'si yavaşladığında (örn. 150ms - 3000ms gecikme ve `HTTP 503`), senkron çalışan bir sunucuda tüm HTTP thread'leri bloke olur ve yeni gelen acil kaza sinyalleri reddedilir.
3. **İnsan Güvenliği Öncelikli Fallback (Safety-Critical Degradation):**
   - Hava yastığı açılmış (`CRITICAL`) bir kazada sigorta poliçe servisi yanıt vermiyor diye çekici ve acil asistans yönlendirmesi bekletilemez.

---

## 2. Doğrulanan Mimari Yetkinlikler ve Kod Karşılıkları

### 2.1. İki Katmanlı Idempotency ve İzole Transaction Yönetimi
- **Kod Referansı:** [`EcallIngestionService.ingest()`](../src/main/java/com/automotive/ecall/service/EcallIngestionService.java)
- **Nasıl Çalışır?**
  1. **Hızlı Yol (Fast-Path):** Önce `findByEventId` ile okuma yapılır; ardışık tekrarlar yazma transaction'ı açılmadan yakalanır.
  2. **Yarış Durumu Koruması (DB Unique Constraint + `PROPAGATION_REQUIRES_NEW`):** Aynı milisaniyede 20 paralel thread aynı `eventId` ile geldiğinde hepsi hızlı yolu geçer. Veritabanındaki `uq_ecall_incidents_event_id` kısıtı sadece 1 thread'in `INSERT` yapmasına izin verir, diğer 19 thread `DataIntegrityViolationException` alır. Yazma işlemi izole bir `TransactionTemplate` (`PROPAGATION_REQUIRES_NEW`) içinde çalıştırıldığı için, hata alan thread'ler zehirlenmiş (`rollbackOnly`) bir Hibernate session'da kalmaz; dışarıda kazanan kaydı okuyup `HTTP 200 OK (idempotentReplay: true)` döner.

### 2.2. Transactional Outbox ve `FOR UPDATE SKIP LOCKED`
- **Kod Referansı:** [`OutboxEventRepository.findPendingBatchForUpdateSkipLocked()`](../src/main/java/com/automotive/ecall/domain/OutboxEventRepository.java)
- **Nasıl Çalışır?**
  - Kaza kaydı (`ecall_incidents`) ve yayınlanacak olay (`outbox_events`) tek bir yerel veritabanı transaction'ı içinde commit edilir.
  - Arka plandaki [`OutboxRelayScheduler`](../src/main/java/com/automotive/ecall/outbox/OutboxRelayScheduler.java) kayıtları `FOR UPDATE SKIP LOCKED` ile çeker. Bu sayede servis birden fazla kopya (multi-instance) olarak ölçeklendiğinde iki sunucu aynı Outbox satırını alıp çifte hasar dosyası açmaz.

### 2.3. Geliştirme Sırasında Yakalanan Gerçek Bir Tuzak: `@Transactional` ve `UnexpectedRollbackException`
- **Kod Referansı:** [`IncidentOrchestrationService.orchestrateIncident()`](../src/main/java/com/automotive/ecall/service/IncidentOrchestrationService.java)
- **Ne Yaşandı?**
  - Sprint 2 testleri sırasında, `MODERATE` seviyeli bir kazada sigorta servisi `HTTP 503` döndüğünde, `IncidentOrchestrationService` olayı `RETRY_SCHEDULED` olarak işaretleyip hatayı `OutboxRelayScheduler`'a yeniden fırlatıyordu.
  - Ancak Spring'in varsayılan `@Transactional` davranışı, metottan bir `RuntimeException` (`ExternalServiceException`) çıktığını gördüğü anda dış transaction'ı `rollbackOnly` olarak işaretledi ve `OutboxRelayScheduler` commit yaparken `UnexpectedRollbackException` patladı!
- **Nasıl Çözüldü?**
  - `@Transactional(noRollbackFor = {ExternalServiceException.class, CallNotPermittedException.class})` tanımı eklenerek, beklenen dış servis hatalarında hem `ecall_incidents` üzerindeki `RETRY_SCHEDULED` durumunun hem de `outbox_events` üzerindeki `retry_count` / `next_attempt_at` (Exponential Backoff) güncellemesinin güvenle commit edilmesi sağlandı.

---

## 3. PoC Senaryo Özeti ve Mimari Kanıt

Detaylı test ortamı ve metrik tabloları [TEST_CONDITIONS_AND_RESULTS.md](TEST_CONDITIONS_AND_RESULTS.md) belgesinde sunulmuştur. Özet bulgular:

- **Senaryo A (650 Paketlik Bölgesel Kaza Fırtınası - 500 Benzersiz + 150 Mükerrer):**
  - Sistem saniyede **~2.698 istek (req/sec)** hızla tüm trafiği karşılamış, **p95 gecikmesi 22.92 ms** (hedef < 60 ms) olarak ölçülmüştür. 150 mükerrer paketin tamamı (%100) yakalanmış ve 500 benzersiz kazanın tamamı için poliçe + çekici ataması (`DISPATCHED_COMPLETE`) tamamlanmıştır.
- **Senaryo B (Sigorta Servisi Çöküşü - 150ms Gecikme + %100 HTTP 503 Hatası Altında 360 Paket):**
  - Dış sigorta servisi tamamen çökmüş olmasına rağmen, **Ingestion API p95 süresi 15.72 ms** kalmıştır (Transactional Outbox sayesinde dış servis yavaşlığı araç TCU'suna hiç yansımamıştır).
  - İlk 5 başarısız çağrıdan sonra **Circuit Breaker `OPEN`** konumuna geçmiş; eğer devre kesici olmasaydı 300 olay $\times$ 150 ms = **45.000 ms (45 saniye)** boyunca bloke olacak arka plan işçisi, devre kesici sayesinde tüm kuyruğu milisaniyeler içinde eriterek 250 `CRITICAL` kazaya acil çekici (`DISPATCHED_FALLBACK`) yönlendirmiştir.
