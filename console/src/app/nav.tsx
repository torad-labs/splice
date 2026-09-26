// The sidebar's groups with their icons: the one place an address meets its picture. The groups
// themselves are plain data in rows.ts; this file only adds what a widget draws.
import type { ReactNode } from 'react';
import { ArrowsInSimpleIcon } from '@phosphor-icons/react/dist/csr/ArrowsInSimple';
import { BellSimpleIcon } from '@phosphor-icons/react/dist/csr/BellSimple';
import { ChartBarIcon } from '@phosphor-icons/react/dist/csr/ChartBar';
import { CubeIcon } from '@phosphor-icons/react/dist/csr/Cube';
import { FolderSimpleIcon } from '@phosphor-icons/react/dist/csr/FolderSimple';
import { IdentificationCardIcon } from '@phosphor-icons/react/dist/csr/IdentificationCard';
import { PlugsIcon } from '@phosphor-icons/react/dist/csr/Plugs';
import { ScrollIcon } from '@phosphor-icons/react/dist/csr/Scroll';
import { SlidersHorizontalIcon } from '@phosphor-icons/react/dist/csr/SlidersHorizontal';
import { StackIcon } from '@phosphor-icons/react/dist/csr/Stack';
import { StethoscopeIcon } from '@phosphor-icons/react/dist/csr/Stethoscope';
import { TerminalWindowIcon } from '@phosphor-icons/react/dist/csr/TerminalWindow';
import { TimerIcon } from '@phosphor-icons/react/dist/csr/Timer';
import { UsersThreeIcon } from '@phosphor-icons/react/dist/csr/UsersThree';
import type { RailGroup } from '@widgets/rail';
import { NAV_GROUPS, type Address } from './rows';
import { PAGE } from './strings';

const ICON: Record<Address, ReactNode> = {
  'needs-you': <BellSimpleIcon />,
  sessions: <TerminalWindowIcon />,
  turns: <TimerIcon />,
  teams: <UsersThreeIcon />,
  projects: <FolderSimpleIcon />,
  fleet: <StackIcon />,
  models: <CubeIcon />,
  compaction: <ArrowsInSimpleIcon />,
  accounts: <IdentificationCardIcon />,
  usage: <ChartBarIcon />,
  settings: <SlidersHorizontalIcon />,
  mcp: <PlugsIcon />,
  logs: <ScrollIcon />,
  doctor: <StethoscopeIcon />,
};

export const RAIL_GROUPS: readonly RailGroup[] = NAV_GROUPS.map((group) => ({
  label: group.label,
  items: group.addresses.map((address) => ({ address, label: PAGE[address], icon: ICON[address] })),
}));
