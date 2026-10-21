# DECISIONS

## Kimlik
- Uygulama adı ADOmination, applicationId com.kaesaki.mangaapp.
- Telemetri kapalı (include-telemetry=false), güncelleme denetleyicisi kapalı.

## Otomatik (sanal) kategoriler
- Tercih: autoCategories, varsayılan KAPALI.
- Sanal kategoriler gerçek kategorilerin SONUNDA; id negatif, -1 ve 0 hariç, etiketten deterministik.
- Korumalar: openChangeCategoryDialog filtresi id > 0; sanal sekmede sıralama DB'ye yazılmaz (setSort id<0 ise çıkar); sanal sekmede "kategoriyi güncelle" gizli; lastUsedCategory sanal sekme için yazılmaz; aralık dışı indeks 0'a düşer.
- Seviyeler: Köken, Format, Hedef Kitle, Tür, Tema. Eşik: en az 3 manga.
- Emin değilsek kategoriye KOYMAYIZ.

## AniList eşleştirme
- Sıra: tracker kaydı -> önbellek -> başlık+yazar araması -> kategorisiz.
- Eşik 0.85, marj 0.04; tek MANGA vs ONE_SHOT/NOVEL kuralı; yazar/ülke ipucu sıkı.
- Köken AniList countryOfOrigin'den (JP Manga, KR Manhwa, CN/TW Manhua).
- Spoiler etiketleri elenir; tag rank eşiği 60.
- Önbellek filesDir/anilist_cache, AniList ID'sine göre; şema sürümü zorunlu (şu an 2). EŞLEŞTİ kalıcı, BELİRSİZ/EŞLEŞMEDİ 30 gün, metadata 90 gün. Hatalar ASLA saklanmaz.
- AniList verisi henüz arayüze bağlı değil (sırada).

## Hiyerarşi (kullanıcı kategorileri)
- İki seviye (ana -> alt); derinlik sonra artırılabilir.
- Manga hem alt hem ana kategoriye doğrudan atanabilir.
- Ana kategori silinince alt kategoriler üst seviyeye çıkar (veri kaybı yok).
- Kategoriler ekranında açılır/kapanır liste.
- Önce manuel; otomatik öneri (ilişkili seriler, spin-off, yazarın eserleri) sonra.
- İleride: genişlet/daralt ve görünür/gizli ayarları (görünürlük alanı migration'da baştan planlanır).
- Sıra: keşif -> veri katmanı (migration, testler, eski yedekler açılmalı) -> düzenleme ekranı -> kütüphane sekmeleri -> genişlet/gizle.