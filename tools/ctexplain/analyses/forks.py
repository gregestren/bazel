# Lint as: python3
# Copyright 2020 The Bazel Authors. All rights reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
"""Analysis that ranks targets by how many configured targets they create.

These are legitimate forks when the configured targets behave differently (i.e.
they require options that differ between them) and trimmable clones when the
they behave identically. Use clones.py to distinguish between these cases.
"""
from typing import Tuple
# Do not edit this line. Copybara replaces it with PY2 migration helper.
from dataclasses import dataclass

from tools.ctexplain.types import ConfiguredTarget


# Tuple of config hashes for all CTs corresponding to the same target.
ConfigHashes = Tuple[str, ...]


@dataclass(frozen=True)
class _Forks():
  """Analysis result."""
  # Tuple of all labels in the graph and config hashes of CTs they create.
  # Sorted by descending popularity. Hashes are sorted lexicographically.
  forks: Tuple[Tuple[str, ConfigHashes], ...]


def analyze(
    cts: Tuple[ConfiguredTarget, ...],
    ) -> _Forks:
  """Runs the analysis.

  Args:
    cts: A build's untrimmed configured targets.

  Returns:
    Analysis result as a _Forks.
  """
  forks = {}
  for ct in cts:
    forks.setdefault(ct.label, []).append(ct.config_hash)
  forks.update((l, tuple(sorted(hashes))) for l, hashes in forks.items())
  ranked = sorted(forks.items(), key=lambda k: (0 - len(k[1]), k[0]))
  return _Forks(tuple(ranked))


def report(result: _Forks):
  """Reports analysis results to the user.

  We intentionally make this its own function to make it easy to support other
  output formats (like machine-readable) if we ever want to do that.

  Args:
    result: the analysis result
  """
  print("  CTs created by each target (these may or may not be wasteful):")
  for label, config_hashes in result.forks:
    pretty_hashes = ", ".join([hash[0:10] for hash in config_hashes])
    ct_or_cts = "CTs" if len(config_hashes) != 1 else "CT"
    print(f"    {label}: {len(config_hashes)} {ct_or_cts} ({pretty_hashes})")
