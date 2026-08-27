import os
import sys

# 使测试离线可跑（无需先 pip install）：直接注入 src 路径
_SRC = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "src")
sys.path.insert(0, _SRC)
