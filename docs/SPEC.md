# Ürün ve Sistem Gereksinimleri Dokümanı (SPEC)
**Proje:** Bağlantılı Araç Çarpışma Algılama (eCall) ve Otomatik Hasar/Asistans Orkestratörü  
**Doküman Durumu:** `FROZEN (v1.0 - İterasyon 3 Onaylı)`  
**Hedef Alan:** Otomotiv Telemetri (Connected Car eCall - EN 15722 MSD) & Sigorta İlk Hasar İhbarı (FNOL - First Notice of Loss)

---

## 1. Problemin Tanımı ve İş Bağlamı (Business Context)

Modern bağlantılı araçlarda (Connected Vehicles) yer alan Telematik Kontrol Ünitesi (TCU), çarpışma sensörleri veya hava yastığı (airbag) tetiklendiğinde **eCall MSD (Minimum Set of Data)** paketini merkez sunuculara iletir. 

Normal şartlarda bu trafik dakikada birkaç olaydan ibarettir. Ancak **şiddetli dolu yağışı, buzlanma kaynaklı zincirleme kazalar veya bölgesel fırtınalar** sırasında saniyede yüzlerce/binlerce araçtan aynı anda çarpışma sinyali gelir. Aynı anda:
1. Araçların içindeki hücresel modemler (TCU) zayıf şebeke nedeniyle **aynı kaza paketini 3 ila 10 kez mükerrer (duplicate)** gönderebilir.
2. Arka uçta çağrılan **Sigorta Poliçe Doğrulama Servisi** ve **Yol Yardım / Çekici Atama (Roadside Assistance) Servisi** aşırı yükten dolayı yavaşlayabilir (2-5 saniye gecikme) veya tamamen yanıt veremez hale gelebilir (`HTTP 503`).

### Çözülen Temel Problemler
- **Sıfır Veri Kaybı (Zero Data Loss):** Dış sigorta veya asistans servisleri çökmüş olsa bile araçtan gelen kaza verisi kesinlikle kabul edilmeli, kalıcı hale getirilmeli ve dış servisler düzeldiğinde işlenmelidir.
- **Tekillik (Idempotency):** Aynı kaza olayı (`eventId`) için ağ tekrarları yüzünden 5 ayrı çekici yönlendirilmemeli ve 5 ayrı sigorta hasar dosyası (FNOL) açılmamalıdır.
- **Hızlı İlk Yanıt (Low-Latency Ingestion):** Araçtaki TCU bağlantıyı açık tutmakta zorlandığı için, ilk karşılama API'si dış servislerin yanıtını beklemeden kabul (`HTTP 202`) dönmelidir.

---

## 2. Fonksiyonel Gereksinimler (Functional Requirements)

### FR-1: eCall Çarpışma Paketi Karşılama (`POST /api/v1/ecall/events`)
Sistem, araçtan gelen aşağıdaki MSD (Minimum Set of Data) verisini kabul etmelidir:
- `eventId` (UUID / String, Zorunlu): Araç TCU'su tarafından kaza anında üretilen benzersiz olay kimliği.
- `vin` (String, 17 karakter, Zorunlu): Aracın şasi numarası (ISO 3779 formatında).
- `crashTimestamp` (ISO-8601 Instant, Zorunlu): Çarpışmanın gerçekleştiği zaman.
- `impactDeltaV` (Double, km/s, Zorunlu): Çarpışma anındaki hız değişimi ($\Delta v \ge 15.0$ km/s).
- `airbagDeployed` (Boolean, Zorunlu): Hava yastığının açılıp açılmadığı bilgisi.
- `rolloverDetected` (Boolean, Zorunlu): Takla sensörü durumu.
- `latitude` / `longitude` (Double, Zorunlu): Geçerli GPS koordinatları (`-90..90` ve `-180..180`).
- `headingDegrees` (Integer, `0..359`): Aracın kaza anındaki yön açısı.
- `passengersCount` (Integer, $\ge 1$): Emniyet kemeri sensörlerinden okunan yolcu sayısı.

### FR-2: Mükerrer İstek Koruması (Idempotent Ingestion)
- Aynı `eventId` ile birden fazla istek gelirse, sistem yeni bir kayıt veya yeni bir asenkron iş emri **oluşturmamalıdır**.
- Araç modeminin (TCU) tekrar göndermeyi kesmesi için hata kodu (`409`) yerine **`200 OK` (veya ilk kayıtta `202 Accepted`)** ile birlikte `idempotentReplay: true` bayrağı ve mevcut `incidentStatus` dönülmelidir.

### FR-3: Şiddet Sınıflandırması (Severity Triage)
Kaza verisi alındığında deterministik kurallarla aciliyet seviyesi (`SeverityLevel`) belirlenir:
- **`CRITICAL`**: `airbagDeployed == true` VEYA `rolloverDetected == true` VEYA `impactDeltaV >= 40.0`
- **`HIGH`**: `impactDeltaV` 25.0 ile 39.9 arası
- **`MODERATE`**: `impactDeltaV` 15.0 ile 24.9 arası

### FR-4: Asenkron Hasar (FNOL) ve Asistans Orkestrasyonu
Kaydedilen her benzersiz kaza olayı için arka planda sırasıyla şu adımlar yürütülür:
1. **Poliçe Doğrulama (Policy Verification):** `vin` ve `crashTimestamp` kullanılarak aracın aktif kasko/trafik poliçesi sorgulanır.
2. **Acil Asistans / Çekici Atama (Roadside & Emergency Dispatch):** Kaza koordinatlarına (`latitude`, `longitude`) ve `SeverityLevel` değerine göre çekici (ve `CRITICAL` ise acil müdahale) talebi oluşturulur.
   - *Kritik İş Kuralı:* Poliçe servisi o an ulaşılamaz durumda olsa bile, eğer kaza **`CRITICAL`** seviyesindeyse insan hayatı/güvenliği gereği **çekici/asistans ataması poliçe yanıtı beklenmeden (Fallback ile)** derhal yapılmalıdır!
3. **Otomatik Hasar Dosyası Açılışı (FNOL Claim Creation):** Poliçe doğrulandıysa sigorta sisteminde ön hasar dosyası (`claimReferenceNumber`) açılır.

### FR-5: Durum Takibi ve Operasyonel Sorgulama
- `GET /api/v1/ecall/incidents/{eventId}`: Belirli bir kaza ihbarının güncel durumunu, atanmış çekici kodunu (`dispatchReference`) ve açılmış hasar dosya numarasını (`claimReference`) döner.
- `GET /api/v1/ecall/stats`: Sistemin işlediği toplam olay, mükerrer engellenen istek, Outbox bekleyen kayıt ve devre kesici (Circuit Breaker) durum özetini döner.

---

## 3. Durum Makinesi (Incident State Machine)

| Durum (`IncidentStatus`) | Açıklama |
| :--- | :--- |
| `RECEIVED` | Araçtan veri alındı, DB'ye ve Outbox tablosuna aynı transaction içinde yazıldı. |
| `PROCESSING` | Arka plan işçisi (Worker) olayı devraldı, dış servis çağrıları yürütülüyor. |
| `DISPATCHED_COMPLETE` | Hem poliçe doğrulandı/hasar dosyası (FNOL) açıldı hem de çekici/asistans atandı. |
| `DISPATCHED_FALLBACK` | Dış sigorta servisi yanıt vermedi (Circuit Breaker açık veya timeout), ancak acil durum kuralları gereği asistans/çekici ataması tamamlandı; poliçe/FNOL mutabakatı sonradan tamamlanmak üzere işaretlendi. |
| `RETRY_SCHEDULED` | Geçici altyapı hatası nedeniyle olay sonraki denemeye bırakıldı. |
| `MANUAL_REVIEW_REQUIRED` | Maksimum deneme sayısına (örn. 5 deneme) ulaşıldı veya poliçe geçersiz/bulunamadı; operatör ekranına düşürüldü (DLQ). |

---

## 4. Fonksiyonel Olmayan Gereksinimler (Non-Functional Requirements & SLAs)

1. **Ingestion Yanıt Süresi (Latency):** Yerel yük testinde 200 eşzamanlı bağlantıda `POST /api/v1/ecall/events` ucu için **p95 < 60ms** olmalıdır (dış servis gecikmelerinden tamamen bağımsız).
2. **Hata Toleransı (Resilience):** Dış sigorta veya asistans servisi %100 hata (`HTTP 500/503`) dönse veya 3 saniye gecikse bile Ingestion API'si **%0 hata** ile kayıt almaya devam etmelidir.
3. **Veri Tutarlılığı (Consistency):** Veritabanına yazılan hiçbir `RECEIVED` olay kaybolmamalıdır (*At-least-once processing* + *Idempotent side effects*).
4. **Gözlemlenebilirlik (Observability):** Spring Boot Actuator ve Micrometer üzerinden Prometheus formatında metrikler (`ecall_ingested_total`, `ecall_duplicate_total`, `ecall_fallback_total`) dışarı açılmalıdır.
