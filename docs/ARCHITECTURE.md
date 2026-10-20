# Kod Haritası ve Mimari (ARCHITECTURE)

Bu belge, projenin modül yapısını, temel ekranlarını, veri akışını ve mimari kararlarını özetleyen kod haritasıdır.

---

## 1. Mimarinin Genel Yapısı (Clean Architecture)

Proje çoklu modül (multi-module) ve **Clean Architecture** prensiplerine göre yapılandırılmıştır.

```
       ┌────────────────────────┐
       │   Arayüz Katmanı       │
       │   (:app, :presentation)│
       └───────────┬────────────┘
                   │  kullanır (Use Case çağrıları)
                   ▼
       ┌────────────────────────┐
       │   İş Mantığı Katmanı   │
       │   (:domain)            │
       └───────────▲────────────┘
                   │  uygular (Repository Implementasyonları)
       ┌───────────┴────────────┐
       │   Veri Katmanı         │
       │   (:data, :source-*)   │
       └────────────────────────┘
```

---

## 2. Gradle Modülleri ve Görevleri

* **`:app`**: Ana Android uygulamasıdır. Ekranlar (Jetpack Compose), navigasyon (Voyager), ViewModel'ler, servisler, arka plan işçileri (WorkManager) ve bağımlılık enjeksiyonunun başlangıç noktası buradadır.
* **`:domain`**: Saf iş mantığı katmanıdır. Ekrandan ve veritabanı detaylarından bağımsızdır. "Manga listesini al", "bölümü okundu işaretle" gibi iş mantığı operasyonları (Use Case / Interactor sınıfları) ve temel modeller buradadır.
* **`:data`**: Veri erişim katmanıdır. SQLite veritabanı (SQLDelight), indirme motoru, repository implementasyonları ve yerel depolama yönetimini içerir.
* **`:presentation-core`**: Arayüz tasarım sistemi çekirdeğidir. Ortak butonlar, diyaloglar, temel renkler ve tipografi tanımlarını barındırır.
* **`:presentation-widget`**: Android ana ekranına (Home Screen) eklenebilen Glance kütüphanesi tabanlı widget'ları barındırır.
* **`:source-api`**: Manga kaynakları (eklentiler/extensions) için kontrat arayüzüdür (`Source`, `HttpSource`, `SManga`, `SChapter`). Eklentiler bu modülü baz alarak geliştirilir.
* **`:source-local`**: İnternetten bağımsız olarak kullanıcının cihazındaki yerel arşivleri/klasörleri okuyan yerel kaynak motorudur (`LocalSource`).
* **`:core:common`**: Tüm modüllerin kullandığı ortak extension'lar, loglama (`logcat`), ayarlar (Preference) altyapısı ve coroutine yardımcı araçları.
* **`:core:archive`**: `.zip`, `.cbz`, `.rar`, `.epub` formatındaki arşiv dosyalarını okuma ve ayıklama motorudur.
* **`:core:metro`**: Metro bağımlılık enjeksiyonu için ortak anotasyonlar ve qualifier tanımlarıdır.
* **`:core-metadata`**: Çizgi roman metadata standardı olan `ComicInfo.xml` dosyalarını okuma ve yazma işlemlerini yürütür.
* **`:i18n`**: Çoklu dil ve yerelleştirme modülüdür (Moko resources).
* **`:icons:*`**: Vektör ikon setlerinin Compose ImageVector karşılıklarıdır (`material-symbols`, `simple-icons`, `custom-material-symbols`).
* **`:telemetry`**: Hata/çökme analitiği modülüdür (`firebase` ve gizlilik odaklı boş `noop` varyantları vardır).
* **`:baseline-profile`**: Android ART için derleme ve başlangıç performans optimizasyonu profilleridir.

---

## 3. Arayüz Katmanı ve Ana Ekranlar

Arayüz `:app` modülünde iki katmanlı olarak geliştirilmiştir:
* `app/src/main/java/eu/kanade/tachiyomi/ui/`: Navigasyon (Voyager), ekran durumu ve mantığını yöneten **ViewModel** sınıfları.
* `app/src/main/java/eu/kanade/presentation/`: Saf **Jetpack Compose** ile yazılmış ekran ve bileşen kodları.

### Ana Ekran Dosyaları:
* **Kütüphane (Library)**:
  * Navigasyon & Tab: `app/src/main/java/eu/kanade/tachiyomi/ui/library/LibraryTab.kt`
  * ViewModel (Mantık): `app/src/main/java/eu/kanade/tachiyomi/ui/library/LibraryViewModel.kt`
  * Görsel Bileşen (UI): `app/src/main/java/eu/kanade/presentation/library/components/LibraryContent.kt`
* **Manga Detay Ekranı**:
  * Ekran Yönetimi: `app/src/main/java/eu/kanade/tachiyomi/ui/manga/MangaScreen.kt`
  * ViewModel (Mantık): `app/src/main/java/eu/kanade/tachiyomi/ui/manga/MangaViewModel.kt`
  * Görsel Bileşen (UI): `app/src/main/java/eu/kanade/presentation/manga/MangaScreen.kt`
* **Okuyucu (Reader)**:
  * Ekran Activity'si: `app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderActivity.kt`
  * ViewModel (Mantık): `app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderViewModel.kt`
  * Sayfa/Webtoon Motorları: `app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/` (`PagerViewer.kt`, `WebtoonViewer.kt`)

---

## 4. Veritabanı ve Okuma İlerlemesi

* **Motor:** Yerel **SQLite**, sorgu ve şema üretimi için **SQLDelight** kullanılır.
* **Veritabanı Dosyası:** `tachiyomi.db` (Tanım: `data/src/main/java/tachiyomi/data/DatabaseBindings.kt`).
* **İlerleme ve Durum Tabloları:**
  * `chapter` tablosu (`data/src/main/sqldelight/tachiyomi/data/chapter.sq`):
    * `user_read`: Bölüm tamamen okundu mu? (Boolean)
    * `user_last_page_read`: Kullanıcının en son kaldığı sayfa numarası (Int).
  * `history` tablosu (`data/src/main/sqldelight/tachiyomi/data/history.sq`):
    * `read_at`: Okumanın gerçekleştiği zaman damgası.
    * `read_duration`: O bölümde geçirilen okuma süresi.
* **Akış:** Okuyucu ekranında sayfa değiştikçe `ReaderViewModel.updateChapterProgress()` çalışır ve `UpdateChapter` use-case'i üzerinden veritabanına kaydedilir. Çıkışta ise `ReaderViewModel.saveHistory()` tetiklenerek geçmiş güncellenir.

---

## 5. Tema ve Renk Sistemi

* **Dinamik ve Özel Temalar:**
  * Tema fonksiyonu: `app/src/main/java/eu/kanade/presentation/theme/TachiyomiTheme.kt`
  * Renk Paletleri: `app/src/main/java/eu/kanade/presentation/theme/colorscheme/` (Nord, Catppuccin, Monet, Green Apple vb.)
* **Temel Tasarım Belirteçleri:**
  * Renkler ve Tipografi: `presentation-core/src/main/java/tachiyomi/presentation/core/theme/Color.kt` ve `Typography.kt`
* **Klasik Android Temaları:**
  * Açılış ve Activity pencereleri: `app/src/main/res/values/themes.xml`

---

## 6. Bağımlılık Enjeksiyonu (Dependency Injection)

* **Kütüphane:** **Metro** (`dev.zacsweers.metro`). Derleme zamanında (compile-time) bağımlılıkları çözer.
* **Graf ve Sağlayıcılar:**
  * `app/src/main/java/mihon/app/di/AppGraph.kt`: Ana DI grafı ve arayüzü.
  * `app/src/main/java/mihon/app/di/AppBindings.kt`: Tekil (singleton) nesne tanımları.
* **Kullanım Biçimi:**
  * Sınıflarda constructor injection için `@Inject` kullanılır.
  * Compose ekranlarında ViewModel temini için `metroViewModel<T>()` fonksiyonu kullanılır.
  * Eski kodlarla uyumluluk için `app/src/main/java/mihon/app/di/injekt/MetroInjektRegistrar.kt` köprüsü mevcuttur.

---

## 7. Takip Servisleri (Örn: AniList)

* **Takipçi Sınıfı:** `app/src/main/java/eu/kanade/tachiyomi/data/track/anilist/Anilist.kt`
* **API ve Ağ:** `app/src/main/java/eu/kanade/tachiyomi/data/track/anilist/AnilistApi.kt` ve `AnilistInterceptor.kt`
* **GraphQL Sorguları:** `app/src/main/graphql/mihon/graphql/anilist/` klasöründeki Apollo GraphQL sorguları.
* **Veritabanı Senkronizasyonu:** `data/src/main/sqldelight/tachiyomi/data/manga_track.sq` içerisindeki `manga_sync` tablosunda harici ID ve ilerleme eşleşmeleri tutulur.
