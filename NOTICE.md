# Сторонние компоненты

VoiceKey собирается вместе с двумя библиотеками; их скачивает `tools/fetch-libs.sh`, в репозитории их нет.

| Компонент | Лицензия |
|---|---|
| [Vosk](https://github.com/alphacep/vosk-api) (`vosk-android` 0.3.75) | Apache-2.0 |
| [JNA](https://github.com/java-native-access/jna) 5.18.1 | Apache-2.0 или LGPL-2.1+ |

Русскую модель распознавания `vosk-model-small-ru` (Apache-2.0, авторы — Alpha Cephei) приложение
само скачивает с alphacephei.com, когда вы включаете офлайн-распознавание.

Приложение `org.vidda.googlevoicesearch` (Vidda-Voice-Service, чужая сборка xxivanoi, 4pda, основана на
приложении Google), через которое работает онлайн-распознавание, в код VoiceKey не входит. Его неизменённый
APK лежит отдельным файлом в релизе `voice-service`, только чтобы VoiceKey мог поставить его по просьбе
пользователя; права на него принадлежат его авторам.

Словари экранного переводчика (`assets/translate/`) основаны на русском переводе строк прошивки из приложения
VIDDA Nova; его автор разрешил использовать этот перевод.
