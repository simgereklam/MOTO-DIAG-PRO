@echo off
chcp 65001 >nul
title Seyfi Usta - Yerel Yapay Zeka Kurulumu
echo ============================================
echo   SEYFI USTA - QWEN YEREL AI KURULUMU
echo ============================================
where ollama >nul 2>nul
if errorlevel 1 (
  echo.
  echo Ollama kurulu degil.
  echo Once resmi Ollama uygulamasini kurun: https://ollama.com/download
  start "" "https://ollama.com/download"
  echo Kurulum tamamlaninca bu dosyayi tekrar calistirin.
  pause
  exit /b 1
)
echo.
echo Ollama servisi baslatiliyor...
start "Ollama Local Server" /min cmd /c "ollama serve"
timeout /t 5 /nobreak >nul
echo.
echo Qwen 2.5 1.5B modeli indiriliyor. Bu ilk kurulumda internet gerekir.
echo Indirme bittikten sonra model bilgisayarda kalir ve uygulama cevrimdisi calisir.
ollama pull qwen2.5:1.5b
if errorlevel 1 (
  echo Model indirilemedi. Ollama servisinin calistigini kontrol edin.
  pause
  exit /b 1
)
echo.
echo KURULUM TAMAMLANDI.
echo Seyfi Usta artik yerel Qwen modelini kullanabilir.
echo Ollama servisini kapatmayin; uygulama acikken arka planda calismali.
pause
