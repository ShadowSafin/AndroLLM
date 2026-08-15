@echo off
adb logcat -v time ChatViewModel:V DefaultEngineRepository:V ToolPlanner:V ToolRunCoordinator:V ModelsViewModel:V LlamaCppEngine:V androllm-llama:V *:S > C:\AndroLLM\live_chat.log 2>&1
