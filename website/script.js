const menuButton = document.querySelector('.menu-toggle');
const navigation = document.querySelector('#primary-nav');

if (menuButton && navigation) {
  const setMenuOpen = (open) => {
    menuButton.setAttribute('aria-expanded', String(open));
    menuButton.setAttribute('aria-label', open ? 'Close navigation' : 'Open navigation');
    navigation.classList.toggle('is-open', open);
  };

  menuButton.addEventListener('click', () => {
    setMenuOpen(menuButton.getAttribute('aria-expanded') !== 'true');
  });

  navigation.addEventListener('click', (event) => {
    if (event.target instanceof HTMLAnchorElement && window.matchMedia('(max-width: 700px)').matches) {
      setMenuOpen(false);
    }
  });

  document.addEventListener('keydown', (event) => {
    if (event.key === 'Escape' && menuButton.getAttribute('aria-expanded') === 'true') {
      setMenuOpen(false);
      menuButton.focus();
    }
  });

  window.addEventListener('resize', () => {
    if (!window.matchMedia('(max-width: 700px)').matches) setMenuOpen(false);
  });
}


const tiltScene = document.querySelector('[data-tilt]');
const finePointer = window.matchMedia('(hover: hover) and (pointer: fine)');
const prefersMotion = window.matchMedia('(prefers-reduced-motion: no-preference)');

if (tiltScene && finePointer.matches && prefersMotion.matches) {
  tiltScene.addEventListener('pointermove', (event) => {
    const bounds = tiltScene.getBoundingClientRect();
    const x = (event.clientX - bounds.left) / bounds.width - 0.5;
    const y = (event.clientY - bounds.top) / bounds.height - 0.5;
    tiltScene.style.setProperty('--tilt-y', `${(x * 12).toFixed(2)}deg`);
    tiltScene.style.setProperty('--tilt-x', `${(-y * 8).toFixed(2)}deg`);
  }, { passive: true });

  tiltScene.addEventListener('pointerleave', () => {
    tiltScene.style.removeProperty('--tilt-x');
    tiltScene.style.removeProperty('--tilt-y');
  });
}
