# Sprint Planı Adversarial İnceleme Kaydı (Principal Engineer Gözüyle)

> **Amaç:** Kodlamaya başlamadan önce hazırlanan Sprint Planının; gerçekçilik, ölçülebilirlik ve "sadece kağıt üzerinde kalan hedefler" açısından eleştirel (Adversarial) bakışla incelenmesi ve düzeltilmesi.

---

## İlk Taslak Sprint Planına Yönelik Eleştiriler

1. **Eleştiri 1: "Testler En Son Sprint'e Bırakılmış!"**
   - *Tespit:* İlk taslakta otomatik testler ve CI kurulumu Sprint 3'te tek bir blok olarak yazılmıştı.
   - *Principal Engineer Yorumu:* "Gerçek mühendislikte testler en son yazılmaz. Sprint 1'de Idempotency ve Outbox yazıyorsan, onun eşzamanlılık (concurrency) testini de Sprint 1'de yazıp yeşil görmelisin. Yoksa Sprint 3'te temel mimariyi baştan değiştirmek zorunda kalırsın."
   - *Düzeltme:* Her Sprint'in kendi **Birim/Entegrasyon Testi Hedefi (Definition of Done)** zorunlu hale getirildi.

2. **Eleştiri 2: "Metrikler Muğlak ('Hızlı çalışmalı', 'Test edilmeli')"**
   - *Tespit:* Sprint başarı kriterlerinde "Sistem hızlı yanıt vermeli" gibi ölçülemez ifadeler vardı.
   - *Principal Engineer Yorumu:* "Ölçemediğin şeyi optimize edemezsin. Her sprint sonunda somut bir sayısal metrik (örn. 'Aynı `eventId` ile 20 paralel thread saldırdığında DB'de tam olarak 1 kayıt oluşmalı, 19 tanesi `idempotentReplay=true` dönmeli') olmalı."
   - *Düzeltme:* Her Sprint sonuna **Somut Ölçüm Metrikleri (Exit Metrics)** tablosu eklendi.

3. **Eleştiri 3: "İstemci (Client) Simülasyonu Sadece Mutlu Yolu (Happy Path) Test Ediyor"**
   - *Tespit:* Client simülatörü sadece rastgele kaza verisi gönderiyordu.
   - *Principal Engineer Yorumu:* "Bu projenin varlık sebebi yüksek trafik, mükerrer paketler ve dış servis çökmesi. İstemci ve dış servis simülatörün özellikle **Kaos (Chaos / Fault Injection)** üretebilmeli: Örneğin dış sigorta servisine 'Şimdi %100 hata dön' veya '3 saniye gecikme ekle' diyebilmeliyiz ki Circuit Breaker'ın gerçekten çalıştığını kanıtlayalım."
   - *Düzeltme:* Sprint 3'teki simülatöre **Fault Injection API** (`/api/v1/simulation/chaos`) ve **TCU Burst/Duplicate Traffic Generator** eklendi.

---

## Karar
Yukarıdaki düzeltmeler `docs/SPRINT_PLAN.md` belgesine işlenmiş ve Sprint Planı **`FROZEN`** statüsüne alınarak kodlama (Execution) fazına geçilmiştir.
