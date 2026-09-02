import { NavLink, Route, Routes } from 'react-router-dom';
import { OverviewPage } from './pages/OverviewPage';
import { ScenariosPage } from './pages/ScenariosPage';
import { ScenarioDetailPage } from './pages/ScenarioDetailPage';
import { RunsPage } from './pages/RunsPage';
import { RunDetailPage } from './pages/RunDetailPage';
import { ProfilesPage } from './pages/ProfilesPage';
import { ImportPage } from './pages/ImportPage';
import { SettingsPage } from './pages/SettingsPage';
import { OperatorAuthBoundary } from './components/OperatorAuth';

/**
 * OutcomeProbe Web UI의 최상위 레이아웃과 라우팅이다.
 *
 * <p>책임: Overview·Scenarios·Runs·Profiles·Import·Settings 화면을 연결하고
 * 상단 내비게이션을 제공한다.</p>
 * <p>외부에서 오해하기 쉬운 한계: YAML 편집기는 제공하지 않는다.
 * Import 화면의 textarea와 파일 붙여넣기가 고급 편집을 대신한다.</p>
 */
export function App() {
  return (
    <OperatorAuthBoundary>
      <div className="layout">
        <header>
          <h1>OutcomeProbe</h1>
          <p className="tagline">
            Break the integration on purpose. Prove the business state still converges.
          </p>
          <nav aria-label="주요 화면">
            <NavLink to="/">Overview</NavLink>
            <NavLink to="/scenarios">Scenarios</NavLink>
            <NavLink to="/runs">Runs</NavLink>
            <NavLink to="/profiles">Profiles</NavLink>
            <NavLink to="/import">Import</NavLink>
            <NavLink to="/settings">Settings</NavLink>
          </nav>
        </header>
        <main>
          <Routes>
            <Route path="/" element={<OverviewPage />} />
            <Route path="/scenarios" element={<ScenariosPage />} />
            <Route path="/scenarios/:name/:version" element={<ScenarioDetailPage />} />
            <Route path="/runs" element={<RunsPage />} />
            <Route path="/runs/:runId" element={<RunDetailPage />} />
            <Route path="/profiles" element={<ProfilesPage />} />
            <Route path="/import" element={<ImportPage />} />
            <Route path="/settings" element={<SettingsPage />} />
          </Routes>
        </main>
      </div>
    </OperatorAuthBoundary>
  );
}
