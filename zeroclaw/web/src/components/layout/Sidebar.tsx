import { NavLink } from 'react-router-dom';
import {
  LayoutDashboard,
  MessageSquare,
  Wrench,
  Clock,
  Puzzle,
  Brain,
  Settings,
  DollarSign,
  Activity,
  Stethoscope,
} from 'lucide-react';
import { t } from '@/lib/i18n';
import { isPageEnabled, type PageId } from '@/platform.config';

const navItems: { to: string; icon: typeof LayoutDashboard; labelKey: string; pageId: PageId }[] = [
  { to: '/', icon: LayoutDashboard, labelKey: 'nav.dashboard', pageId: 'dashboard' },
  { to: '/agent', icon: MessageSquare, labelKey: 'nav.agent', pageId: 'agent' },
  { to: '/tools', icon: Wrench, labelKey: 'nav.tools', pageId: 'tools' },
  { to: '/cron', icon: Clock, labelKey: 'nav.cron', pageId: 'cron' },
  { to: '/integrations', icon: Puzzle, labelKey: 'nav.integrations', pageId: 'integrations' },
  { to: '/memory', icon: Brain, labelKey: 'nav.memory', pageId: 'memory' },
  { to: '/config', icon: Settings, labelKey: 'nav.config', pageId: 'config' },
  { to: '/cost', icon: DollarSign, labelKey: 'nav.cost', pageId: 'cost' },
  { to: '/logs', icon: Activity, labelKey: 'nav.logs', pageId: 'logs' },
  { to: '/doctor', icon: Stethoscope, labelKey: 'nav.doctor', pageId: 'doctor' },
];

export default function Sidebar() {
  return (
    <aside className="fixed top-0 left-0 h-screen w-60 bg-gray-900 flex flex-col border-r border-gray-800">
      {/* Logo / Title */}
      <div className="flex items-center gap-2 px-5 py-5 border-b border-gray-800">
        <div className="h-8 w-8 rounded-lg bg-blue-600 flex items-center justify-center text-white font-bold text-sm">
          ZC
        </div>
        <span className="text-lg font-semibold text-white tracking-wide">
          ZeroClaw
        </span>
      </div>

      {/* Navigation */}
      <nav className="flex-1 overflow-y-auto py-4 px-3 space-y-1">
        {navItems.map(({ to, icon: Icon, labelKey, pageId }) => {
          const enabled = isPageEnabled(pageId);
          return (
            <NavLink
              key={to}
              to={enabled ? to : '#'}
              end={to === '/'}
              className={({ isActive }) =>
                [
                  'flex items-center gap-3 px-3 py-2.5 rounded-lg text-sm font-medium transition-colors',
                  !enabled
                    ? 'opacity-40 pointer-events-none text-gray-300'
                    : isActive
                      ? 'bg-blue-600 text-white'
                      : 'text-gray-300 hover:bg-gray-800 hover:text-white',
                ].join(' ')
              }
              title={!enabled ? 'Managed in app settings' : undefined}
              tabIndex={!enabled ? -1 : undefined}
              aria-disabled={!enabled || undefined}
            >
              <Icon className="h-5 w-5 flex-shrink-0" />
              <span>{t(labelKey)}</span>
            </NavLink>
          );
        })}
      </nav>
    </aside>
  );
}
