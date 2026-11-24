# SinusChat

Komunikator na Androida z szyfrowaniem end-to-end protokołem Signal w każdej rozmowie, rozmowach i grupach. Konto zakłada się na adres e-mail albo bez żadnego adresu. Strona: [sinusoide.pl](https://sinusoide.pl).

- wiadomości, zdjęcia, pliki, głosówki i połączenia szyfrowane na telefonie,
- grupy, społeczności, rozmowy grupowe i krótkofalówka w sieci lokalnej,
- tryb Tor, tryb „Bez Google” (powiadomienia przez ntfy zamiast Firebase),
- tłumaczenie wiadomości w całości na telefonie (Bergamot / Firefox Translations),
- asystent Sinia działający offline,
- baza danych zawsze zaszyfrowana (SQLCipher), opcjonalny PIN i PIN awaryjny.

## Budowanie

Potrzebne: Android Studio (lub sam Android SDK), JDK 21 i NDK w wersji podanej w `app/build.gradle.kts`.

```bash
./gradlew assembleDebug
```

Moduł `link` (połączenie z komputerem) jest częścią tego repozytorium i buduje się razem z aplikacją.

Powiadomienia przez Firebase są opcjonalne. Bez nich aplikacja działa w trybie „Bez Google”. Żeby je włączyć we własnej wersji, dopisz do `local.properties` wartości własnego projektu Firebase:

```properties
fcm.apiKey=
fcm.appId=
fcm.projectId=
fcm.senderId=
```

`local.properties`, `google-services.json`, klucze do podpisywania i zbudowane pliki są w `.gitignore` i nigdy nie trafiają do repozytorium.

## Kopie dla innych aplikacji

Inne aplikacje (np. Export To Graphene) mogą poprosić o kopię zapasową przez `startActivityForResult` z akcją `pl.sinusoide.sinuschat.action.EXPORT_BACKUP`. SinusChat zawsze pokazuje, która aplikacja pyta, i wymaga potwierdzenia PIN-em, biometrią lub blokadą ekranu. Po zgodzie zwraca `RESULT_OK` z adresem `content://` pliku `SinusChat-rrrr-MM-dd-GGmm.sinuschat` (`application/octet-stream`, tylko odczyt) i dodatkiem `backup_key_id` (identyfikator klucza w hex). Opcjonalny dodatek `with_media` (boolean) ustawia domyślny wybór zdjęć i plików. Bez zgody wynik to `RESULT_CANCELED` z dodatkiem `error` (`cancelled`, `no_caller`, `not_ready`, `no_recovery_key`, `failed`). Plik jest zaszyfrowany kluczem odzyskiwania (12 słów), którego SinusChat nigdy nie przekazuje, i jest usuwany po 10 minutach lub przy następnym eksporcie.

Plik `.sinuschat` otwarty przez `ACTION_VIEW` albo `ACTION_SEND` (`content://`) trafia do SinusChat. Przed konfiguracją aplikacji jest odkładany i po zalogowaniu w „Mam już konto — przenieś dane” SinusChat proponuje jego przywrócenie. W skonfigurowanej aplikacji kopię można tylko sprawdzić.

## Serwer

Kod serwera, z którym łączy się aplikacja, jest w osobnym repozytorium: [SinusChat-server](https://github.com/sinusoidetechnology/SinusChat-server).

## Licencja

AGPL-3.0 z warunkami dodatkowymi z sekcji 7 (nazwy i znaki, oznaczenie zmienionej wersji, autorstwo, poparcie) — pełny tekst w [LICENSE](LICENSE), warunki w [NOTICE.md](NOTICE.md). Zmieniona wersja musi mieć własną nazwę, ikonę i identyfikator aplikacji.

## Bezpieczeństwo

Błędy bezpieczeństwa zgłaszaj prywatnie, według [SECURITY.md](SECURITY.md).

## English

SinusChat is an Android messenger with end-to-end encryption (Signal protocol) for every chat, call and group. Accounts use an e-mail address or no address at all. Build it with `./gradlew assembleDebug` (JDK 21, Android SDK and the NDK version from `app/build.gradle.kts`). Firebase push is optional; without the `fcm.*` values in `local.properties` the app runs in its "No Google" mode. Licensed under AGPL-3.0 with additional terms, see [LICENSE](LICENSE) and [NOTICE.md](NOTICE.md). Report security issues privately as described in [SECURITY.md](SECURITY.md).

Other apps can request a backup with the `pl.sinusoide.sinuschat.action.EXPORT_BACKUP` activity action. After the user confirms (naming the calling app) and unlocks with PIN, biometrics or the screen lock, the result is `RESULT_OK` with a read-only `content://` URI of an encrypted `.sinuschat` file and the `backup_key_id` extra; otherwise `RESULT_CANCELED` with an `error` extra. The recovery key is never shared. Opening a `.sinuschat` file (`ACTION_VIEW`/`ACTION_SEND`) before setup offers to restore it after signing in with "I already have an account"; after setup the backup can only be checked.
