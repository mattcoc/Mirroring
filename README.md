# Mirroring

App Android per vedere e controllare lo schermo di un dispositivo da un altro sulla stessa rete Wi‑Fi (telefono ↔ tablet).

## Installazione

Scarica l'APK dall'ultima [release](https://github.com/mattcoc/Mirroring/releases/latest) e installalo su entrambi i dispositivi. Gli aggiornamenti successivi si scaricano dall'app, in **Impostazioni**.

## Uso

1. Sul dispositivo da controllare premi **Avvia condivisione**.
2. Per il touch remoto premi **Attiva** e abilita Mirroring tra i servizi di accessibilità. Se Android lo blocca: Impostazioni → App → Mirroring → ⋮ → **Consenti impostazioni con restrizioni**.
3. Sull'altro dispositivo tocca il nome del dispositivo che compare nella lista.

Su Android 17 concedi il permesso **Dispositivi nelle vicinanze**, necessario per la rete locale.

## Come funziona

- Video: MediaProjection → H.264 (MediaCodec) → TCP porta 8765
- Ricerca dispositivi: broadcast UDP sulla porta 8766
- Touch: servizio di accessibilità con gesti continui (Android 8+)
