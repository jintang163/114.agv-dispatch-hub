#!/usr/bin/env python3
"""
仓储 AGV 模拟器：启动 N 台虚拟叉车，经 MQTT 与调度中心交互。

上行（本模拟器 -> 调度中心）
  agv/{code}/status  retained，1s 周期：位置/边进度/阶段/载货/电量/速度/航向
  agv/{code}/event   任务与设备事件：
    TASK_ACCEPTED / TASK_STARTED / NODE_ARRIVED / PICKED_UP / DROPPED
    CHARGING_STARTED / TASK_COMPLETED / PREEMPTED / FAULT / RECOVERED

下行（调度中心 -> 本模拟器）
  agv/{code}/task  新任务（含完整 path；type=CHARGING 为自动充电任务，
                   chargeDwell=在桩充电时长，targetBattery=目标电量）
  agv/{code}/cmd   CANCEL / PATH_UPDATE / FAULT / RESET / HOLD / RESUME
"""
import json
import math
import os
import threading
import time
from urllib.parse import urlparse

import paho.mqtt.client as mqtt
import requests

BROKER = os.getenv("BROKER", "tcp://localhost:1883")
_parsed = urlparse(BROKER)
BROKER_HOST = _parsed.hostname or "localhost"
BROKER_PORT = _parsed.port or 1883
MAP_URL = os.getenv("MAP_URL", "http://localhost:8080/api/map")
ROBOTS_URL = os.getenv("ROBOTS_URL", "http://localhost:8080/api/robots")
ROBOT_COUNT = int(os.getenv("ROBOT_COUNT", "6"))
TICK = float(os.getenv("TICK_SECONDS", "1.0"))
FAULT_RATE = float(os.getenv("FAULT_RATE", "0"))     # 每 tick 自然故障概率，演示用
# 行驶放电速率（%/秒），调快以便演示低电自动回充
DISCHARGE_RATE = float(os.getenv("DISCHARGE_RATE", "0.12"))


class VirtualAGV:
    def __init__(self, code, anchor, client, battery=90.0, coords=None):
        self.code = code
        self.node = anchor
        self.client = client
        self.coords = coords or {}
        self.lock = threading.Lock()

        self.state = "IDLE"            # IDLE / BUSY / CHARGING / FAULT
        self.task_id = None
        self.task_type = "TRANSPORT"   # TRANSPORT / PICKING / CHARGING
        self.path = []
        self.idx = 0                   # 当前边：path[idx] -> path[idx+1]
        self.next_node = None
        self.progress = 0.0
        self.loaded = False
        self.phase = None
        self.step_seconds = 2.0
        self.pickup_dwell = 4.0
        self.drop_dwell = 4.0
        self.charge_dwell = 30.0
        self.charge_target = 95.0
        self.charge_rate = 0.0
        self.dwell_left = 0.0
        self.hold_until = 0.0
        self.snap_node = None
        self.started_sent = False
        self.battery = float(battery)
        self.speed = 0.0
        self.heading = 0.0
        self.from_node = None
        self.to_node = None

    # ------------- MQTT 通信 -------------
    def status_topic(self):
        return f"agv/{self.code}/status"

    def event_topic(self):
        return f"agv/{self.code}/event"

    def publish_event(self, etype, task_id=None, **extra):
        payload = {"type": etype, "taskId": task_id, "ts": time.time()}
        payload.update(extra)
        self.client.publish(self.event_topic(), json.dumps(payload), qos=1)
        print(f"[{self.code}] event {etype}" + (f" task={task_id}" if task_id else ""))

    def publish_status(self):
        with self.lock:
            payload = {
                "status": self.state,
                "node": self.node,
                "nextNode": self.next_node,
                "progress": round(self.progress, 3),
                "phase": self.phase,
                "loaded": self.loaded,
                "pathIndex": self.idx,
                "battery": max(0, round(self.battery)),
                "speed": round(self.speed, 1),
                "heading": round(self.heading, 1),
                "ts": time.time(),
            }
        self.client.publish(self.status_topic(), json.dumps(payload), qos=1, retain=True)

    # ------------- 几何（航向/速度） -------------
    def _edge_velocity(self, a, b):
        """由地图坐标推算航向角（0=东，90=南）与边上匀速（单位/秒）。"""
        pa, pb = self.coords.get(a), self.coords.get(b)
        if not pa or not pb:
            return 0.0, self.heading
        dx, dy = pb[0] - pa[0], pb[1] - pa[1]
        length = math.hypot(dx, dy)
        heading = math.degrees(math.atan2(dy, dx))
        speed = length / max(0.1, self.step_seconds)
        return speed, heading

    # ------------- 下行消息 -------------
    def on_task(self, msg):
        data = json.loads(msg.payload)
        with self.lock:
            old_task = self.task_id
            preempt = bool(data.get("preempt")) and old_task is not None
            if preempt:
                self.publish_event("PREEMPTED", old_task)
            # 抢占时车辆可能正在旧路径的边中央：先沿当前边抵达接管节点再并入新路径
            finishing_node = self.next_node if preempt else None
            self.snap_node = finishing_node
            self.task_id = data["taskId"]
            self.task_type = data.get("type", "TRANSPORT")
            self.path = data["path"]
            self.idx = 0
            self.progress = 0.0
            self.loaded = False
            self.dwell_left = 0.0
            self.started_sent = False
            self.step_seconds = float(data.get("stepSeconds", 2))
            self.pickup_dwell = float(data.get("pickupDwell", 4))
            self.drop_dwell = float(data.get("dropDwell", 4))
            self.charge_dwell = float(data.get("chargeDwell", 30))
            self.charge_target = float(data.get("targetBattery", 95))
            self.from_node = data.get("from")
            self.to_node = data.get("to")
            self.state = "BUSY"
            self.next_node = self.path[1] if len(self.path) > 1 else None
            if self.next_node:
                self.speed, self.heading = self._edge_velocity(self.path[0], self.next_node)
            delay = float(data.get("startDelay", 0))
            self.hold_until = time.time() + delay

            if self.task_type == "CHARGING":
                # 充电任务无取货：已在桩上（单节点路径）则直接开始充电
                self.phase = "GOING_CHARGER"
                if len(self.path) <= 1 or self.path[0] == self.to_node:
                    self._begin_charging()
            elif self.path and self.path[0] == self.from_node:
                # 机器人已在取货点：等待延迟结束后完成取货停靠再出发
                self.phase = "AT_PICKUP"
                self.dwell_left = self.pickup_dwell
        if delay:
            print(f"[{self.code}] 发车延迟 {delay:.0f}s（等待路口时间窗）")
        self.publish_event("TASK_ACCEPTED", self.task_id)

    def on_cmd(self, msg):
        data = json.loads(msg.payload)
        cmd = data.get("type")
        if cmd == "CANCEL":
            with self.lock:
                old = self.task_id
                self._abort_task()
            if old is not None:
                self.publish_event("TASK_CANCELLED", old)
        elif cmd == "PATH_UPDATE":
            self._apply_path_update(data)
        elif cmd == "FAULT":
            reason = data.get("reason", "模拟故障")
            with self.lock:
                old = self.task_id
                self.state = "FAULT"
            self.publish_event("FAULT", old, reason=reason)
        elif cmd == "RESET":
            with self.lock:
                self.state = "IDLE"
                self._abort_task()
            self.publish_event("RECOVERED")
        elif cmd in ("HOLD", "RESUME"):
            print(f"[{self.code}] cmd {cmd}")

    def _abort_task(self):
        self.task_id = None
        self.task_type = "TRANSPORT"
        self.path = []
        self.idx = 0
        self.next_node = None
        self.progress = 0.0
        self.loaded = False
        self.phase = None
        self.dwell_left = 0.0
        self.hold_until = 0.0
        self.snap_node = None
        self.started_sent = False
        self.speed = 0.0
        self.state = "IDLE"

    def _apply_path_update(self, data):
        new_path = data["path"]
        with self.lock:
            if self.state not in ("BUSY", "CHARGING"):
                return
            if self.next_node and self.next_node in new_path:
                # 继续当前边，抵达后按新路径走
                self.path = new_path
                self.idx = new_path.index(self.next_node) - 1
            elif self.node in new_path:
                self.path = new_path
                self.idx = new_path.index(self.node)
                self.progress = 0.0
                self.next_node = self.path[self.idx + 1] if self.idx + 1 < len(self.path) else None
            if self.next_node:
                self.speed, self.heading = self._edge_velocity(self.node, self.next_node)
        self.hold_until = time.time() + float(data.get("startDelay", 0))
        self.publish_event("PATH_UPDATED", self.task_id, path=new_path)

    # ------------- 充电 -------------
    def _begin_charging(self):
        # 持锁调用：抵达充电桩（或下发时已在桩）
        self.state = "CHARGING"
        self.phase = "CHARGING"
        self.next_node = None
        self.progress = 0.0
        self.speed = 0.0
        self.dwell_left = self.charge_dwell
        # 按在桩时长线性补足到目标电量，保证停靠结束时恰好充到 targetBattery
        need = max(0.0, self.charge_target - self.battery)
        self.charge_rate = need / max(1.0, self.charge_dwell)
        self.publish_event("CHARGING_STARTED", self.task_id, node=self.node)

    # ------------- 每个 tick 的运动仿真 -------------
    def tick(self):
        # 自然故障注入（仅行驶中）
        if FAULT_RATE > 0 and self.state == "BUSY" and __import__("random").random() < FAULT_RATE:
            with self.lock:
                old = self.task_id
                self.state = "FAULT"
            self.publish_event("FAULT", old, reason="随机故障注入")
            return

        with self.lock:
            # 在桩充电倒计时（state=CHARGING，不走行驶逻辑）
            if self.state == "CHARGING" and self.dwell_left > 0:
                self.battery = min(100.0, self.battery + self.charge_rate * TICK)
                self.dwell_left -= TICK
                if self.dwell_left <= 0:
                    self._finish_dwell()
                return

            if self.state != "BUSY" or not self.path:
                # 空闲/故障：静置，不做任何"魔法补电"（回充统一走充电任务）
                return

            self.battery = max(0.0, self.battery - DISCHARGE_RATE * TICK)

            if not self.started_sent:
                self.started_sent = True
                self.publish_event("TASK_STARTED", self.task_id)

            # 停靠倒计时（取货 / 卸货）
            if self.dwell_left > 0:
                self.dwell_left -= TICK
                if self.dwell_left <= 0:
                    self._finish_dwell()
                return

            # 抢占后需先走完好边剩余半边（snap），该动作不受避让等待影响
            if self.snap_node is None and time.time() < self.hold_until:
                return  # 调度器指定的发车延迟，原地等待

            # 沿边行驶
            self.progress += TICK / max(0.1, self.step_seconds)
            if self.progress < 1.0:
                return
            self.progress = 0.0
            if self.snap_node is not None:
                # 抢占后走完旧边的剩余半边，在接管节点并入新路径
                arrived = self.snap_node
                self.snap_node = None
                self.node = arrived
                self.idx = self.path.index(arrived) if arrived in self.path else 0
            else:
                self.idx += 1
                self.node = self.path[self.idx]
                arrived = self.node
            self.next_node = self.path[self.idx + 1] if self.idx + 1 < len(self.path) else None
            is_pickup = (self.task_type != "CHARGING"
                         and not self.loaded and arrived == self.from_node)
            is_end = self.idx == len(self.path) - 1

            if self.next_node:
                self.speed, self.heading = self._edge_velocity(arrived, self.next_node)
            else:
                self.speed = 0.0

            # 抵达终点：充电任务→在桩充电；搬运/拣选→卸货停靠
            if is_end and self.task_type == "CHARGING":
                self.battery = min(100.0, self.battery)
                self._begin_charging()
                return

        if is_pickup:
            with self.lock:
                self.phase = "AT_PICKUP"
                self.dwell_left = self.pickup_dwell
            self.publish_event("NODE_ARRIVED", self.task_id, node=arrived)
        elif is_end:
            with self.lock:
                self.phase = "AT_DELIVERY"
                self.dwell_left = self.drop_dwell
            self.publish_event("NODE_ARRIVED", self.task_id, node=arrived)
        else:
            self.publish_event("NODE_ARRIVED", self.task_id, node=arrived)

    def _finish_dwell(self):
        # 持锁调用
        if self.phase == "AT_PICKUP":
            self.loaded = True
            self.phase = "GOING_DELIVERY"
            self.publish_event("PICKED_UP", self.task_id, node=self.node)
        elif self.phase == "AT_DELIVERY":
            self.phase = "DONE"
            self.loaded = False
            self.publish_event("DROPPED", self.task_id, node=self.node)
            finished = self.task_id
            self._abort_task()
            self.publish_event("TASK_COMPLETED", finished, node=self.node)
        elif self.phase == "CHARGING":
            # 充满，结束充电任务恢复空闲
            self.battery = max(self.battery, self.charge_target)
            finished = self.task_id
            self._abort_task()
            self.publish_event("TASK_COMPLETED", finished, node=self.node,
                               battery=round(self.battery))


def fetch_robots_and_map():
    """拉取机器人列表（编号/锚点/初始电量）与地图节点坐标。"""
    robots = None
    for _ in range(60):
        try:
            resp = requests.get(ROBOTS_URL, timeout=3)
            robots = resp.json()
            break
        except Exception:
            time.sleep(2)
    if robots is None:
        raise RuntimeError("无法从调度中心获取机器人列表")

    coords = {}
    for _ in range(60):
        try:
            data = requests.get(MAP_URL, timeout=3).json()
            coords = {n["code"]: (n["x"], n["y"]) for n in data.get("nodes", [])}
            if coords:
                break
        except Exception:
            time.sleep(2)
    return robots, coords


def main():
    robots_meta, coords = fetch_robots_and_map()
    anchors = {r["code"]: r.get("currentNode") for r in robots_meta}
    batteries = {r["code"]: r.get("battery", 90) for r in robots_meta}
    codes = sorted(anchors.keys())[:ROBOT_COUNT]
    agvs = {}
    clients = []

    for code in codes:
        client = mqtt.Client(
            client_id=f"emulator-{code}",
            callback_api_version=mqtt.CallbackAPIVersion.VERSION2,
        )
        client.will_set(f"agv/{code}/status",
                        json.dumps({"status": "OFFLINE", "ts": time.time()}),
                        qos=1, retain=True)
        agv = VirtualAGV(code, anchors[code], client,
                         battery=batteries.get(code, 90), coords=coords)
        agvs[code] = agv

        def make_subscribers(a):
            def on_message(c, userdata, msg):
                try:
                    if msg.topic.endswith("/task"):
                        a.on_task(msg)
                    elif msg.topic.endswith("/cmd"):
                        a.on_cmd(msg)
                except Exception as e:
                    print(f"[{a.code}] 消息处理异常: {e}")
            return on_message

        client.on_message = make_subscribers(agv)
        for attempt in range(60):
            try:
                client.connect(BROKER_HOST, BROKER_PORT, keepalive=15)
                break
            except Exception:
                time.sleep(2)
        else:
            raise RuntimeError(f"{code} 无法连接 MQTT Broker {BROKER}")
        client.loop_start()
        client.subscribe(f"agv/{code}/task", qos=1)
        client.subscribe(f"agv/{code}/cmd", qos=1)
        clients.append(client)
        # 上线：发一帧 IDLE 状态
        agv.state = "IDLE"
        agv.publish_status()
        print(f"{code} 上线，初始锚点 {anchors[code]}，电量 {agv.battery:.0f}%")

    print(f"共启动 {len(agvs)} 台 AGV，tick={TICK}s，放电速率 {DISCHARGE_RATE}%/s")
    try:
        while True:
            for agv in agvs.values():
                agv.tick()
            for agv in agvs.values():
                agv.publish_status()
            time.sleep(TICK)
    except KeyboardInterrupt:
        for c in clients:
            c.disconnect()


if __name__ == "__main__":
    main()
