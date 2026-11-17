# Spec & TDD 3-Aşamalı İnceleme ve Evrim Kaydı (Review Log)

> **Amaç:** Bu belge, `SPEC.md` (Fonksiyonel Gereksinimler) ve `TDD.md` (Teknik Tasarım Belgesi) dökümanlarının dondurulmadan (`FROZEN`) önce geçirdiği 3 farklı inceleme iterasyonunu ve her iterasyonda yapılan düzeltmeleri kayıt altına alır.

---

## İterasyon 1: Okunabilirlik ve Uygulanabilirlik İncelemesi (Clarity & Implementability)

### Tespit Edilen Sorunlar (V0.1 Taslağı Üzerinden)
1. **Belirsiz Payload Tanımı:** İlk taslakta "araçtan kaza verisi gelir" denilmişti ancak hangi alanların zorunlu olduğu, koordinat formatı veya darbe şiddetinin (G-force) nasıl ifade edileceği net değildi. Kodu yazacak geliştirici DTO'yu oluştururken tahminde bulunmak zorunda kalacaktı.
2. **Durum Geçişleri (State Machine) Eksikliği:** Bir kaza ihbarının (Incident) hangi durumlardan geçeceği (`RECEIVED` -> `POLICY_VERIFIED` -> `CLAIM_OPENED` vb.) açık bir tablo halinde verilmemişti.
3. **Hata Kodları Karmaşası:** Aynı `eventId` ile mükerrer istek geldiğinde `409 Conflict` mi yoksa `200 OK` (Idempotent yanıt) mı dönüleceği netleştirilmemişti.

### Yapılan Düzeltmeler (V0.2'ye Geçiş)
- Avrupa Birliği eCall standardından (EN 15722 - *Minimum Set of Data / MSD*) esinlenen somut bir JSON şeması (`eventId`, `vin`, `crashTimestamp`, `impactDeltaV`, `airbagDeployed`, `gpsCoordinates`, `directionDegrees`) tanımlandı.
- İstemci (araç içindeki TCU - Telematics Control Unit) ağ kopması yaşadığında aynı paketi tekrar göndereceği için, mükerrer isteklerde `409` yerine **mevcut kaydın durumuyla birlikte `200 OK` (`idempotentReplay: true`)** dönülmesi kararlaştırıldı. Böylece araç modemi gereksiz yere retry döngüsüne girmeyecek.
- Açık ve deterministik bir durum makinesi (`RECEIVED`, `PROCESSING`, `DISPATCHED`, `PARTIAL_FALLBACK`, `MANUAL_REVIEW_REQUIRED`) eklendi.

---

## İterasyon 2: Bütünlük ve Bağlam Uyumu İncelemesi (Integrity & Context Matching)

### Tespit Edilen Sorunlar (V0.2 Taslağı Üzerinden)
1. **Otomotiv ve Sigorta Bağlamının Kopukluğu:** Otomotiv tarafı (Mercedes/Renault) aracı `VIN` (17 haneli şasi numarası) ile tanırken, sigorta şirketi hasar dosyasını (FNOL - *First Notice of Loss*) `policyNumber` (Poliçe No) ve teminat limitleri üzerinden açar. İki dünya arasındaki köprü TDD'de eksik kalmıştı.
2. **Senkron vs Asenkron Çelişkisi:** Spec'te "Ingestion API < 50ms altında yanıt vermeli" denirken, TDD'nin ilk halinde poliçe sorgulama ve çekici atama işlemleri aynı HTTP thread'i içinde senkron çağrılıyordu. Dış sigorta API'si 2 saniye gecikirse 50ms hedefi imkansız hale geliyordu.
3. **Veritabanı ve Mesaj Kuyruğu Bütünlüğü (Dual-Write Problemi):** Veritabanına `incident` kaydını atıp hemen ardından Kafka'ya mesaj göndermek, Kafka o an ayakta değilse verinin DB'de kalıp hiçbir zaman işlenmemesine yol açıyordu.

### Yapılan Düzeltmeler (V0.3'e Geçiş)
- **Asenkron Ayrıştırma (Decoupling):** `POST /api/v1/ecall/events` ucu sadece veriyi doğrular, mükerrerlik kontrolü yapar ve tek bir yerel veritabanı transaction'ı içinde hem `ecall_incidents` hem de `outbox_events` tablosuna kayıt atarak **`202 Accepted`** döner.
- **VIN -> Poliçe Eşleşmesi ve Önbellek:** Poliçe doğrulama ve FNOL hasar dosyası açma işlemleri arka planda Outbox/Event tüketicisi tarafından yürütülür. Sık sorgulanan aktif poliçe özetleri yerel önbellekte tutularak dış servis yükü azaltılır.
- **Transactional Outbox Pattern:** Dual-write problemini çözmek için Outbox tablosu zorunlu hale getirildi.

---

## İterasyon 3: Adversarial İnceleme (Principal Engineer Gözüyle Eleştirel Bakış)

> **Senaryo:** Bu dokümanı ve kodu Mercedes-Benz veya büyük bir sigorta şirketindeki **Principal Engineer** inceliyor. Aday bir **stajyer / genç yetenek**. Principal Engineer neyi takdir eder, nerede "Bu çocuk kopyala-yapıştır yapmış veya boyundan büyük soyutlamalara girmiş" der?

### Principal Engineer'ın Sert Soruları ve Bulduğu Açıklar
1. **"Gereksiz Soyutlama (Over-Engineering) Tuzağı":**
   - *Eleştiri:* "Tek bir domain'i olan projede neden 15 tane `AbstractGenericEventFactoryBean`, Hexagonal Architecture adı altında 4 katmanlı mapper ve birbirinin kopyası 3 farklı DTO katmanı var? Stajyer seviyesinde bu kadar soyutlama genellikle ezbere yapıldığını gösterir."
   - *Çözüm:* Paket yapısı sadeleştirildi. Gereksiz Generic/Factory soyutlamaları tamamen çıkarıldı. Sadece **gerçekten ihtiyaç duyulan 3 temel desen** (Transactional Outbox, Strategy/Adapter ile Broker soyutlaması, Resilience4j ile Circuit Breaker/Fallback) bırakıldı ve her birinin *neden* seçildiği mülakatta savunulabilecek netlikte yazıldı.
2. **"Veritabanı Optimizasyonu İddianı Kanıtla":**
   - *Eleştiri:* "Outbox tablosunu her 500ms'de bir `SELECT * FROM outbox_events WHERE status = 'PENDING'` ile tararsan, tabloda 2 milyon `PROCESSED` kayıt biriktiğinde PostgreSQL Sequential Scan yapar ve CPU %100'e vurur. Ayrıca 2 instance çalıştırdığında aynı outbox kaydını iki sunucu aynı anda okur!"
   - *Çözüm (Güçlü Yanımızı Gösterdiğimiz Yer):*
     - Outbox tablosuna **Partial Index** (`CREATE INDEX idx_outbox_pending ON outbox_events (created_at) WHERE status = 'PENDING'`) eklendi. Böylece tabloda milyonlarca işlenmiş kayıt olsa bile indeks sadece bekleyen birkaç yüz kaydı tutar!
     - Çoklu sunucu (multi-instance) çakışmasını önlemek için sorguya **`FOR UPDATE SKIP LOCKED`** eklendi.
     - Aynı anda gelen mükerrer kaza bildirimlerinde uygulama seviyesindeki `existsByEventId` kontrolü *Race Condition* altında delinir; bu yüzden DB seviyesinde `UNIQUE (event_id)` kısıtı ve durum güncellemelerinde `@Version` (**Optimistic Locking**) zorunlu kılındı.
3. **"Batching ve Üst Düzey Optimizasyonlarda Dürüstlük":**
   - *Eleştiri:* "Her şeyi %100 çözdüğünü iddia eden dökümanlar şüphe çekicidir. Sistemin sınırları ne?"
   - *Çözüm:* TDD'ye **"Bilinen Kısıtlar ve Gelişime Açık Alanlar (Known Limitations & Trade-offs)"** bölümü eklendi. Örneğin: *"Outbox okuyucu şu an kayıtları sabit boyutlu (batch size = 50) gruplar halinde çekip sırayla işlemektedir; Kafka'ya tam asenkron pipelining veya JDBC batch rewrite optimizasyonu henüz temel düzeyde tutulmuştur."* Bu dürüstlük, adayın neyi bilip neyi henüz deneyimlemediğinin farkında olan, **yüksek potansiyelli ve güvenilir bir genç mühendis** olduğunu kanıtlar.

---

## Sonuç: Dondurma Kararı (`STATUS: FROZEN`)
Yukarıdaki 3 iterasyon sonucunda `docs/SPEC.md` ve `docs/TDD.md` belgeleri son haline getirilmiş ve geliştirme fazı için **FROZEN (Kilitli)** statüsüne alınmıştır.
