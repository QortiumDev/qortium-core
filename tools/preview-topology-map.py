#!/usr/bin/env python3
"""
Collect Previewnet peer topology from the operator nodes and render an SVG map.

The default map queries the five operator nodes:

  L local desktop
  Q Qubes/Kicksecure VM
  M Mac mini
  N Netcup seed
  R Regxa seed

It writes a JSON snapshot plus an SVG diagram. If ImageMagick is available, it
also exports a PNG unless --no-png is supplied.
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import math
import shutil
import subprocess
import sys
from dataclasses import dataclass
from html import escape
from pathlib import Path
from typing import Any
from urllib.parse import urlparse


REPO_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_OUT_DIR = REPO_ROOT / "target" / "preview-topology"
QUBES_HANDOFF_DIR = Path("/home/user/Documents/qortium-kicksecure-vpn-ssh-handoff")
QUBES_SSH_CONFIG = QUBES_HANDOFF_DIR / "ssh_config.onion"

API_PORT = 24891
CHAIN_PORT = 24892
DATA_PORT = 24894

COLORS = {
    "IP_CHAIN": "#16a34a",
    "IP_DATA": "#dc2626",
    "I2P_CHAIN": "#2563eb",
    "I2P_DATA": "#f97316",
    "unknown": "#6b7280",
}


@dataclass(frozen=True)
class NodeConfig:
    key: str
    label: str
    name: str
    role: str
    public_host: str | None = None
    ssh: tuple[str, ...] | None = None
    cwd: Path | None = None


NODE_CONFIGS = [
    NodeConfig("local", "L", "Local", "participant"),
    NodeConfig(
        "qubes",
        "Q",
        "Qubes",
        "participant",
        ssh=(
            "ssh",
            "-F",
            str(QUBES_SSH_CONFIG),
            "-o",
            "BatchMode=yes",
            "-o",
            "ConnectTimeout=45",
            "qortium-kicksecure-vpn-onion",
        ),
        cwd=QUBES_HANDOFF_DIR,
    ),
    NodeConfig(
        "mac",
        "M",
        "Mac",
        "participant",
        ssh=("ssh", "-o", "BatchMode=yes", "-o", "ConnectTimeout=10", "qortium-macmini"),
    ),
    NodeConfig(
        "netcup",
        "N",
        "Netcup",
        "seed",
        public_host="185.207.104.78",
        ssh=("ssh", "-o", "BatchMode=yes", "-o", "ConnectTimeout=10", "qortium-netcup"),
    ),
    NodeConfig(
        "regxa",
        "R",
        "Regxa",
        "seed",
        public_host="146.103.42.59",
        ssh=("ssh", "-o", "BatchMode=yes", "-o", "ConnectTimeout=10", "qortium-regxa"),
    ),
]


def run_command(args: list[str] | tuple[str, ...], cwd: Path | None, timeout: int) -> str:
    result = subprocess.run(
        list(args),
        cwd=str(cwd) if cwd else None,
        check=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        timeout=timeout,
    )
    return result.stdout


def shell_quote(value: str) -> str:
    return "'" + value.replace("'", "'\"'\"'") + "'"


def fetch_endpoint(node: NodeConfig, endpoint: str, timeout: int) -> Any:
    url = f"http://localhost:{API_PORT}{endpoint}"
    if node.ssh:
        command = f"curl -fsS --max-time {timeout} {shell_quote(url)}"
        output = run_command((*node.ssh, command), node.cwd, timeout + 55)
    else:
        output = run_command(["curl", "-fsS", "--max-time", str(timeout), url], None, timeout + 5)
    return json.loads(output)


def host_part(address: str | None) -> str | None:
    if not address:
        return None

    if "://" not in address:
        address = "//" + address
    parsed = urlparse(address)
    if parsed.hostname:
        return parsed.hostname

    if ":" in address:
        return address.rsplit(":", 1)[0]
    return address


def capability(peer: dict[str, Any], name: str) -> Any:
    for item in peer.get("capabilities") or []:
        if name in item:
            return item[name]
    return None


def edge_kind(layer: str, transport: str | None) -> str:
    normalized = (transport or "IP").upper()
    if layer == "chain":
        return "I2P_CHAIN" if normalized == "I2P" else "IP_CHAIN"
    return "I2P_DATA" if normalized == "I2P" else "IP_DATA"


def collect_nodes(timeout: int) -> dict[str, Any]:
    snapshot: dict[str, Any] = {
        "generatedAt": dt.datetime.now(dt.timezone.utc).isoformat(),
        "nodes": {},
        "errors": {},
    }

    for node in NODE_CONFIGS:
        entry: dict[str, Any] = {
            "label": node.label,
            "name": node.name,
            "role": node.role,
            "publicHost": node.public_host,
        }
        try:
            entry["info"] = fetch_endpoint(node, "/admin/info", timeout)
            entry["status"] = fetch_endpoint(node, "/admin/status", timeout)
            entry["chainPeers"] = fetch_endpoint(node, "/peers", timeout)
            entry["dataPeers"] = fetch_endpoint(node, "/peers/data", timeout)
        except Exception as exc:  # noqa: BLE001 - capture per-node collection failures
            entry["error"] = str(exc)
            snapshot["errors"][node.key] = str(exc)
        snapshot["nodes"][node.key] = entry

    return snapshot


def build_topology(snapshot: dict[str, Any], max_extra_peers: int) -> dict[str, Any]:
    named_by_key = snapshot["nodes"]
    label_by_key = {key: node["label"] for key, node in named_by_key.items()}
    label_by_chain_node_id: dict[str, str] = {}
    label_by_chain_endpoint: dict[str, str] = {}
    public_host_labels: dict[str, set[str]] = {}

    for key, node in named_by_key.items():
        label = node["label"]
        info = node.get("info") or {}
        node_id = info.get("nodeId")
        if node_id:
            label_by_chain_node_id[node_id] = label

        public_host = node.get("publicHost")
        if public_host:
            label_by_chain_endpoint[f"{public_host}:{CHAIN_PORT}"] = label
            label_by_chain_endpoint[f"{public_host}:{DATA_PORT}"] = label
            public_host_labels.setdefault(public_host, set()).add(label)

    # Learn public hosts for named non-seed nodes from seed peer lists.
    for node in named_by_key.values():
        for peer in node.get("chainPeers") or []:
            peer_label = label_by_chain_node_id.get(peer.get("nodeId"))
            host = host_part(peer.get("address"))
            if peer_label and host:
                public_host_labels.setdefault(host, set()).add(peer_label)

    i2p_qdn_to_label: dict[str, str] = {}
    i2p_chain_to_label: dict[str, str] = {}
    for node in named_by_key.values():
        for peer in node.get("chainPeers") or []:
            peer_label = label_by_chain_node_id.get(peer.get("nodeId"))
            if not peer_label:
                continue
            i2p_chain = capability(peer, "I2P")
            i2p_qdn = capability(peer, "I2P_QDN")
            if i2p_chain:
                i2p_chain_to_label[i2p_chain] = peer_label
            if i2p_qdn:
                i2p_qdn_to_label[i2p_qdn] = peer_label

    graph_nodes: dict[str, dict[str, Any]] = {}
    extra_nodes: dict[str, dict[str, Any]] = {}
    edges: dict[tuple[str, str, str], dict[str, Any]] = {}

    for node in named_by_key.values():
        label = node["label"]
        status = node.get("status") or {}
        graph_nodes[label] = {
            "id": label,
            "label": label,
            "kind": "operator",
            "role": node.get("role"),
            "name": node.get("name"),
            "host": node.get("publicHost"),
            "chainCount": status.get("numberOfConnections", len(node.get("chainPeers") or [])),
            "dataCount": status.get("numberOfDataConnections", len(node.get("dataPeers") or [])),
            "status": status.get("syncPhase"),
            "height": status.get("height"),
            "version": (node.get("info") or {}).get("buildVersion"),
            "observedBy": [],
        }

    def make_extra_id(host: str | None, peer: dict[str, Any]) -> str:
        if host:
            return f"X:{host}"
        node_id = peer.get("nodeId")
        if node_id:
            return f"X:{node_id}"
        return f"X:{peer.get('address', 'unknown')}"

    def add_extra(extra_id: str, host: str | None, peer: dict[str, Any], connected_to: str) -> str:
        if extra_id not in extra_nodes:
            label = f"P{len(extra_nodes) + 1}"
            extra_nodes[extra_id] = {
                "id": extra_id,
                "label": label,
                "kind": "observed",
                "role": "peer",
                "host": host,
                "nodeIds": set(),
                "versions": set(),
                "connectedTo": set(),
                "chainCount": 0,
                "dataCount": 0,
                "observedBy": set(),
            }
        extra = extra_nodes[extra_id]
        if peer.get("nodeId"):
            extra["nodeIds"].add(peer["nodeId"])
        if peer.get("version"):
            extra["versions"].add(peer["version"])
        extra["connectedTo"].add(connected_to)
        extra["observedBy"].add(connected_to)
        return extra_id

    def add_edge(a: str, b: str, kind: str, source_key: str, peer: dict[str, Any], layer: str) -> None:
        if a == b:
            return
        pair = tuple(sorted((a, b)))
        edge_key = (pair[0], pair[1], kind)
        edge = edges.setdefault(
            edge_key,
            {
                "source": pair[0],
                "target": pair[1],
                "kind": kind,
                "samples": [],
                "count": 0,
            },
        )
        edge["count"] += 1
        edge["samples"].append(
            {
                "reportedBy": source_key,
                "direction": peer.get("direction"),
                "address": peer.get("address"),
                "transport": peer.get("transport"),
                "version": peer.get("version"),
                "nodeId": peer.get("nodeId"),
            }
        )
        for node_id in (a, b):
            node = graph_nodes.get(node_id) or extra_nodes.get(node_id)
            if not node or node.get("kind") == "operator":
                continue
            if layer == "chain":
                node["chainCount"] = int(node.get("chainCount") or 0) + 1
            else:
                node["dataCount"] = int(node.get("dataCount") or 0) + 1

    def resolve_chain_target(peer: dict[str, Any], current_label: str) -> str:
        node_id = peer.get("nodeId")
        if node_id in label_by_chain_node_id:
            return label_by_chain_node_id[node_id]

        address = peer.get("address")
        if address in label_by_chain_endpoint:
            return label_by_chain_endpoint[address]

        host = host_part(address)
        if host and host in i2p_chain_to_label:
            return i2p_chain_to_label[host]

        extra_id = make_extra_id(host, peer)
        return add_extra(extra_id, host, peer, current_label)

    def resolve_data_target(peer: dict[str, Any], current_label: str) -> str | None:
        address = peer.get("address")
        host = host_part(address)
        endpoint_label = label_by_chain_endpoint.get(address)
        if endpoint_label:
            return endpoint_label

        if host and host in i2p_qdn_to_label:
            return i2p_qdn_to_label[host]

        # If a seed sees a data connection from a host that uniquely belongs to a
        # named node, map it back to that node. Ambiguous shared public hosts are
        # left as extra peer nodes.
        if host and host in public_host_labels and len(public_host_labels[host]) == 1:
            return next(iter(public_host_labels[host]))

        extra_id = make_extra_id(host, peer)
        return add_extra(extra_id, host, peer, current_label)

    for key, node in named_by_key.items():
        label = node["label"]
        for peer in node.get("chainPeers") or []:
            if peer.get("handshakeStatus") != "COMPLETED":
                continue
            target = resolve_chain_target(peer, label)
            add_edge(label, target, edge_kind("chain", peer.get("transport")), key, peer, "chain")

        for peer in node.get("dataPeers") or []:
            if peer.get("handshakeStatus") != "COMPLETED":
                continue
            target = resolve_data_target(peer, label)
            if target:
                add_edge(label, target, edge_kind("data", peer.get("transport")), key, peer, "data")

    # Keep the diagram readable if the seed nodes are connected to many outside peers.
    if max_extra_peers >= 0 and len(extra_nodes) > max_extra_peers:
        allowed = set(list(extra_nodes)[:max_extra_peers])
        extra_nodes = {key: value for key, value in extra_nodes.items() if key in allowed}
        edges = {
            key: value
            for key, value in edges.items()
            if not (value["source"].startswith("X:") and value["source"] not in allowed)
            and not (value["target"].startswith("X:") and value["target"] not in allowed)
        }

    extras_serializable = {}
    for key, value in extra_nodes.items():
        value["peerCount"] = int(value.get("chainCount") or 0) + int(value.get("dataCount") or 0)
        extras_serializable[key] = {
            **value,
            "nodeIds": sorted(value["nodeIds"]),
            "versions": sorted(value["versions"]),
            "connectedTo": sorted(value["connectedTo"]),
            "observedBy": sorted(value["observedBy"]),
        }
        graph_nodes[key] = extras_serializable[key]

    for value in graph_nodes.values():
        value["peerCount"] = int(value.get("chainCount") or 0) + int(value.get("dataCount") or 0)

    return {
        "namedLabels": label_by_key,
        "graphNodes": graph_nodes,
        "extraNodes": extras_serializable,
        "edges": list(edges.values()),
    }


def node_radius(node: dict[str, Any], max_peer_count: int) -> float:
    peer_count = max(1, int(node.get("peerCount") or 0))
    max_count = max(1, max_peer_count)
    return 16 + math.sqrt(peer_count / max_count) * 35


def layout_graph(
    graph_nodes: dict[str, dict[str, Any]],
    edges: list[dict[str, Any]],
    width: int,
    height: int,
    summary_height: int,
) -> dict[str, tuple[float, float]]:
    ids = sorted(
        graph_nodes,
        key=lambda node_id: (-int(graph_nodes[node_id].get("peerCount") or 0), graph_nodes[node_id]["label"]),
    )
    if not ids:
        return {}

    max_peer_count = max(int(node.get("peerCount") or 0) for node in graph_nodes.values())
    radii = {node_id: node_radius(graph_nodes[node_id], max_peer_count) for node_id in ids}
    left, right = 70.0, width - 70.0
    top, bottom = 118.0, height - summary_height - 46.0
    cx, cy = width / 2, (top + bottom) / 2
    usable_radius = max(170.0, min(right - left, bottom - top) * 0.38)

    positions: dict[str, list[float]] = {}
    for index, node_id in enumerate(ids):
        if index == 0:
            positions[node_id] = [cx, cy]
            continue
        angle = index * math.pi * (3 - math.sqrt(5))
        radius = usable_radius * math.sqrt(index / max(1, len(ids) - 1))
        positions[node_id] = [cx + math.cos(angle) * radius, cy + math.sin(angle) * radius]

    weighted_edges = [
        (edge["source"], edge["target"], max(1, int(edge.get("count") or 1)))
        for edge in edges
        if edge["source"] in positions and edge["target"] in positions
    ]

    centrality = {node_id: float(graph_nodes[node_id].get("peerCount") or 0) for node_id in ids}
    for source, target, weight in weighted_edges:
        centrality[source] += weight
        centrality[target] += weight
    max_centrality = max(centrality.values()) or 1.0

    def clamp(node_id: str) -> None:
        radius = radii[node_id]
        x, y = positions[node_id]
        x = min(max(x, left + radius), right - radius)
        y = min(max(y, top + radius), bottom - radius)

        # Keep nodes clear of the legend box. Edges can pass behind it.
        if x < 405 and y < 218:
            if 405 - x < 218 - y:
                x = 405 + radius
            else:
                y = 218 + radius
        positions[node_id] = [x, y]

    for node_id in ids:
        clamp(node_id)

    for _ in range(520):
        forces = {node_id: [0.0, 0.0] for node_id in ids}

        for i, a in enumerate(ids):
            ax, ay = positions[a]
            for b in ids[i + 1 :]:
                bx, by = positions[b]
                dx, dy = bx - ax, by - ay
                distance = max(1.0, math.hypot(dx, dy))
                min_distance = radii[a] + radii[b] + 18
                repel = 7200 / (distance * distance)
                if distance < min_distance:
                    repel += (min_distance - distance) * 0.9
                fx, fy = dx / distance * repel, dy / distance * repel
                forces[a][0] -= fx
                forces[a][1] -= fy
                forces[b][0] += fx
                forces[b][1] += fy

        for source, target, weight in weighted_edges:
            sx, sy = positions[source]
            tx, ty = positions[target]
            dx, dy = tx - sx, ty - sy
            distance = max(1.0, math.hypot(dx, dy))
            desired = 175 + radii[source] + radii[target] - min(weight, 4) * 12
            pull = (distance - desired) * 0.014 * min(weight, 4)
            fx, fy = dx / distance * pull, dy / distance * pull
            forces[source][0] += fx
            forces[source][1] += fy
            forces[target][0] -= fx
            forces[target][1] -= fy

        for node_id in ids:
            x, y = positions[node_id]
            central_pull = 0.002 + 0.008 * (centrality[node_id] / max_centrality)
            forces[node_id][0] += (cx - x) * central_pull
            forces[node_id][1] += (cy - y) * central_pull
            positions[node_id][0] += forces[node_id][0]
            positions[node_id][1] += forces[node_id][1]
            clamp(node_id)

    return {node_id: (coords[0], coords[1]) for node_id, coords in positions.items()}


def render_svg(snapshot: dict[str, Any], topology: dict[str, Any]) -> str:
    width = 1400
    graph_nodes = topology["graphNodes"]
    summary_rows = sorted(
        graph_nodes.values(),
        key=lambda node: (-int(node.get("peerCount") or 0), node["label"]),
    )
    summary_columns = 4 if len(summary_rows) > 12 else 3
    rows_per_column = max(1, math.ceil(len(summary_rows) / summary_columns))
    summary_height = 54 + rows_per_column * 20
    height = max(1080, 900 + summary_height)
    positions = layout_graph(graph_nodes, topology["edges"], width, height, summary_height)
    max_peer_count = max((int(node.get("peerCount") or 0) for node in graph_nodes.values()), default=1)

    def line_for_edge(edge: dict[str, Any], offset: float) -> str:
        if edge["source"] not in positions or edge["target"] not in positions:
            return ""
        x1, y1 = positions[edge["source"]]
        x2, y2 = positions[edge["target"]]
        dx, dy = x2 - x1, y2 - y1
        length = math.hypot(dx, dy) or 1
        nx, ny = -dy / length, dx / length
        x1 += nx * offset
        y1 += ny * offset
        x2 += nx * offset
        y2 += ny * offset
        color = COLORS.get(edge["kind"], COLORS["unknown"])
        dash = ' stroke-dasharray="8 7"' if edge["kind"].startswith("I2P") else ""
        width_attr = 2.2 if edge["kind"].endswith("CHAIN") else 2.8
        title = escape(
            f"{edge['kind']} count={edge['count']} "
            + "; ".join(sample.get("address") or "" for sample in edge["samples"][:4])
        )
        return (
            f'<line x1="{x1:.1f}" y1="{y1:.1f}" x2="{x2:.1f}" y2="{y2:.1f}" '
            f'stroke="{color}" stroke-width="{width_attr}" stroke-linecap="round" '
            f'opacity="0.82"{dash}><title>{title}</title></line>'
        )

    pair_kinds: dict[tuple[str, str], list[str]] = {}
    for edge in topology["edges"]:
        pair = tuple(sorted((edge["source"], edge["target"])))
        pair_kinds.setdefault(pair, []).append(edge["kind"])

    edge_svg = []
    order = {"IP_CHAIN": 0, "IP_DATA": 1, "I2P_CHAIN": 2, "I2P_DATA": 3}
    for edge in sorted(topology["edges"], key=lambda e: (e["source"], e["target"], order.get(e["kind"], 9))):
        pair = tuple(sorted((edge["source"], edge["target"])))
        kinds = sorted(pair_kinds[pair], key=lambda k: order.get(k, 9))
        index = kinds.index(edge["kind"])
        offset = (index - (len(kinds) - 1) / 2) * 10
        edge_svg.append(line_for_edge(edge, offset))

    node_svg = []
    for node_id, node in sorted(
        graph_nodes.items(),
        key=lambda item: (int(item[1].get("peerCount") or 0), item[1]["label"]),
    ):
        if node_id not in positions:
            continue
        x, y = positions[node_id]
        radius = node_radius(node, max_peer_count)
        label = node["label"]
        role = node.get("role")
        kind = node.get("kind")
        if role == "seed":
            fill = "#ecfdf5"
            stroke = "#065f46"
        else:
            fill = "#f8fafc"
            stroke = "#64748b"
        font_size = max(10, min(30, radius * (0.72 if len(label) <= 2 else 0.5)))
        title_parts = [
            str(node.get("name") or node.get("host") or label),
            f"label={label}",
            f"chain={node.get('chainCount', 0)}",
            f"data={node.get('dataCount', 0)}",
            f"total={node.get('peerCount', 0)}",
        ]
        if node.get("version"):
            title_parts.append(str(node["version"]))
        if node.get("versions"):
            title_parts.extend(str(version) for version in node["versions"])
        node_svg.append(
            f'''
            <g class="node">
              <circle cx="{x:.1f}" cy="{y:.1f}" r="{radius:.1f}" fill="{fill}" stroke="{stroke}" stroke-width="2.8"/>
              <text x="{x:.1f}" y="{y + font_size * 0.34:.1f}" class="node-label" font-size="{font_size:.1f}px">{escape(label)}</text>
              <title>{escape(' | '.join(title_parts))}</title>
            </g>
            '''
        )

    generated = snapshot.get("generatedAt", "")
    summary_cells = []
    for index, node in enumerate(summary_rows):
        column = index // rows_per_column
        row = index % rows_per_column
        x = 28 + column * 335
        y = height - summary_height + 37 + row * 20
        text = (
            f"{node['label']}: total {node.get('peerCount', 0)}, "
            f"chain {node.get('chainCount', 0)}, data {node.get('dataCount', 0)}"
        )
        summary_cells.append(f'<text x="{x}" y="{y}" class="summary">{escape(text)}</text>')

    legend = f'''
      <g class="legend" transform="translate(28 34)">
        <rect x="0" y="0" width="340" height="164" rx="8" fill="#ffffff" stroke="#cbd5e1"/>
        <text x="18" y="28" class="legend-title">Previewnet topology</text>
        <line x1="20" y1="52" x2="82" y2="52" stroke="{COLORS['IP_CHAIN']}" stroke-width="3"/>
        <text x="96" y="57" class="legend-text">IP chain connection</text>
        <line x1="20" y1="78" x2="82" y2="78" stroke="{COLORS['IP_DATA']}" stroke-width="3"/>
        <text x="96" y="83" class="legend-text">IP QDN/data connection</text>
        <line x1="20" y1="104" x2="82" y2="104" stroke="{COLORS['I2P_CHAIN']}" stroke-width="3" stroke-dasharray="8 7"/>
        <text x="96" y="109" class="legend-text">I2P chain connection</text>
        <line x1="20" y1="130" x2="82" y2="130" stroke="{COLORS['I2P_DATA']}" stroke-width="3" stroke-dasharray="8 7"/>
        <text x="96" y="135" class="legend-text">I2P QDN/data connection</text>
        <text x="18" y="154" class="legend-small">Circle size follows chain + data peer count.</text>
      </g>
    '''

    summary_text = "\n".join(summary_cells)

    return f'''<?xml version="1.0" encoding="UTF-8"?>
<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" viewBox="0 0 {width} {height}">
  <style>
    .title {{ font: 700 26px system-ui, -apple-system, Segoe UI, sans-serif; fill: #0f172a; }}
    .subtitle {{ font: 13px system-ui, -apple-system, Segoe UI, sans-serif; fill: #475569; }}
    .node-label {{ font-family: system-ui, -apple-system, Segoe UI, sans-serif; font-weight: 800; text-anchor: middle; fill: #0f172a; }}
    .legend-title {{ font: 700 16px system-ui, -apple-system, Segoe UI, sans-serif; fill: #0f172a; }}
    .legend-text {{ font: 13px system-ui, -apple-system, Segoe UI, sans-serif; fill: #334155; }}
    .legend-small {{ font: 11px system-ui, -apple-system, Segoe UI, sans-serif; fill: #64748b; }}
    .summary {{ font: 13px ui-monospace, SFMono-Regular, Menlo, monospace; fill: #334155; }}
  </style>
  <rect width="100%" height="100%" fill="#f8fafc"/>
  <g class="edges">
    {''.join(edge_svg)}
  </g>
  <g class="nodes">
    {''.join(node_svg)}
  </g>
  <rect x="405" y="14" width="590" height="64" rx="8" fill="#f8fafc" opacity="0.94"/>
  <text x="700" y="42" class="title" text-anchor="middle">Qortium Previewnet live topology</text>
  <text x="700" y="66" class="subtitle" text-anchor="middle">Generated {escape(generated)} from /admin/status, /peers, and /peers/data</text>
  {legend}
  <g class="summary-panel">
    <rect x="18" y="{height - summary_height - 12}" width="{width - 36}" height="{summary_height}" rx="8" fill="#ffffff" stroke="#cbd5e1"/>
    <text x="28" y="{height - summary_height + 12}" class="legend-title">Counts: chain + data peers</text>
    {summary_text}
  </g>
</svg>
'''


def export_png(svg_path: Path, png_path: Path) -> None:
    converter = shutil.which("magick") or shutil.which("convert")
    if not converter:
        raise RuntimeError("ImageMagick convert/magick not found")

    if Path(converter).name == "magick":
        command = [converter, str(svg_path), str(png_path)]
    else:
        command = [converter, str(svg_path), str(png_path)]
    run_command(command, None, 60)


def main() -> int:
    parser = argparse.ArgumentParser(description="Map Qortium Previewnet chain/data topology.")
    parser.add_argument("--output-dir", type=Path, default=DEFAULT_OUT_DIR)
    parser.add_argument("--svg", type=Path, default=None, help="SVG output path")
    parser.add_argument("--snapshot", type=Path, default=None, help="JSON snapshot output path")
    parser.add_argument("--png", type=Path, default=None, help="PNG output path")
    parser.add_argument("--no-png", action="store_true", help="Do not attempt PNG export")
    parser.add_argument("--timeout", type=int, default=8, help="Per-endpoint curl timeout in seconds")
    parser.add_argument(
        "--max-extra-peers",
        type=int,
        default=40,
        help="Maximum number of non-operator peers to draw; use -1 for unlimited",
    )
    args = parser.parse_args()

    args.output_dir.mkdir(parents=True, exist_ok=True)
    svg_path = args.svg or (args.output_dir / "preview-topology.svg")
    snapshot_path = args.snapshot or (args.output_dir / "preview-topology.json")
    png_path = args.png or (args.output_dir / "preview-topology.png")

    snapshot = collect_nodes(args.timeout)
    topology = build_topology(snapshot, args.max_extra_peers)
    snapshot["topology"] = topology

    snapshot_path.write_text(json.dumps(snapshot, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    svg_path.write_text(render_svg(snapshot, topology), encoding="utf-8")

    print(f"Wrote snapshot: {snapshot_path}")
    print(f"Wrote SVG: {svg_path}")

    if not args.no_png:
        try:
            export_png(svg_path, png_path)
            print(f"Wrote PNG: {png_path}")
        except Exception as exc:  # noqa: BLE001 - PNG is a convenience export
            print(f"PNG export skipped: {exc}", file=sys.stderr)

    if snapshot.get("errors"):
        print("Collection completed with node errors:", file=sys.stderr)
        for node, error in snapshot["errors"].items():
            print(f"  {node}: {error}", file=sys.stderr)
        return 2

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
