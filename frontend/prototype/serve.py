#!/usr/bin/env python3
"""Serve the static prototype from its own directory without browser caching."""

from argparse import ArgumentParser
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path


class PrototypeHandler(SimpleHTTPRequestHandler):
    def end_headers(self) -> None:
        self.send_header("Cache-Control", "no-store, no-cache, must-revalidate")
        self.send_header("Pragma", "no-cache")
        self.send_header("Expires", "0")
        super().end_headers()

    def do_GET(self) -> None:
        if self.path.split("?", 1)[0] == "/favicon.ico":
            self.send_response(204)
            self.end_headers()
            return
        super().do_GET()


def main() -> None:
    parser = ArgumentParser(description="Run the Store Analytics HTML prototype")
    parser.add_argument("--port", type=int, default=4173)
    args = parser.parse_args()

    prototype_directory = Path(__file__).resolve().parent
    handler = lambda *handler_args, **handler_kwargs: PrototypeHandler(
        *handler_args,
        directory=str(prototype_directory),
        **handler_kwargs,
    )
    server = ThreadingHTTPServer(("127.0.0.1", args.port), handler)
    print(f"Prototype: http://127.0.0.1:{args.port}")
    print(f"Directory: {prototype_directory}")
    print("Press Ctrl+C to stop")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nPrototype stopped")
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
