# Источники данных Road Limit Reminder

## OpenStreetMap

Дорожная геометрия и скоростные ограничения происходят из OpenStreetMap.

- Проект данных: https://www.openstreetmap.org/
- Авторское право и ODbL: https://www.openstreetmap.org/copyright
- Ключ maxspeed: https://wiki.openstreetmap.org/wiki/Key:maxspeed

## Подготовленные SQLite-базы

Для мобильного приложения используются готовые country SQLite-базы из проекта:

https://github.com/hvdwolf/SpeedAlert_Databases

Документация формата и локального поиска:

https://github.com/hvdwolf/SpeedAlert/blob/main/LocalDatabases.md

Лицензия баз — CC BY 4.0. В релизе europe-202610, опубликованном 29 октября 2026 года, присутствует Беларусь: by.sqlite.7z размером 4 581 935 байт.

## Использование в приложении

- Код страны определяется через Android Geocoder.
- Для страны выбирается соответствующая SQLite-база по ISO 3166-1 alpha-2 коду.
- Базы хранятся во внутреннем каталоге приложения.
- Если базы нет, она скачивается из GitHub Releases.
- Проверка обновления выполняется раз в 30 дней.
- Lookup выполняется локально по bounding boxes и геометрии Google encoded polyline.
- В базу не записывается история GPS-поездок; координаты не отправляются на сервер поиска дорог.

## Ограничения

- Наличие конкретной country database зависит от опубликованных релизов проекта SpeedAlert_Databases.
- Точность значения зависит от качества и актуальности исходных тегов OSM.
- Если в OSM не указан maxspeed, приложение не выводит значение на основании догадки.
- Не используются Overpass API, 2ГИС, HERE или платные картографические сервисы.

## Лицензии и атрибуция

- OpenStreetMap contributors — ODbL.
- HvdWolf / SpeedAlert_Databases prepared SQLite datasets — CC BY 4.0.
- Приложение указывает оба источника на экране.
