import os
import sys

# 使测试可导入平级模块（protocol / core / secrets / ...）
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
