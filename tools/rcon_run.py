#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Minimal RCON client used by the isolated-server verification runs.

Usage: python tools/rcon_run.py <port> <password> <command> [<command> ...]
Prints one "== <command>" header per command followed by the raw response lines.
"""
import socket
import struct
import sys
import time

SERVERDATA_AUTH = 3
SERVERDATA_EXECCOMMAND = 2


def pack(req_id, kind, body):
    data = struct.pack('<ii', req_id, kind) + body.encode('utf8') + b'\x00\x00'
    return struct.pack('<i', len(data)) + data


def read_packet(sock):
    raw = b''
    while len(raw) < 4:
        chunk = sock.recv(4 - len(raw))
        if not chunk:
            return None
        raw += chunk
    (length,) = struct.unpack('<i', raw)
    body = b''
    while len(body) < length:
        chunk = sock.recv(length - len(body))
        if not chunk:
            break
        body += chunk
    if len(body) < 10:
        return None
    req_id, kind = struct.unpack('<ii', body[:8])
    payload = body[8:-2].decode('utf8', 'replace')
    return req_id, kind, payload


def main():
    port = int(sys.argv[1])
    password = sys.argv[2]
    commands = sys.argv[3:]
    sock = socket.create_connection(('127.0.0.1', port), timeout=10)
    sock.sendall(pack(1, SERVERDATA_AUTH, password))
    response = read_packet(sock)
    if response is None or response[0] != 1:
        print('RCON auth failed:', response)
        return 2
    print('RCON authenticated')
    req_id = 10
    for command in commands:
        print('== ' + command)
        sock.sendall(pack(req_id, SERVERDATA_EXECCOMMAND, command))
        sock.settimeout(1.5)
        collected = []
        while True:
            try:
                packet = read_packet(sock)
            except socket.timeout:
                break
            if packet is None:
                break
            collected.append(packet[2])
        for chunk in collected:
            for line in chunk.replace('\r', '').split('\n'):
                if line.strip():
                    print(line)
        req_id += 1
        time.sleep(0.3)
    sock.close()
    return 0


if __name__ == '__main__':
    sys.exit(main())
